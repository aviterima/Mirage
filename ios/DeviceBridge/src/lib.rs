//! Original Mirage adapter over the MIT-licensed, revision-pinned idevice library.
//! Every connection and borrowed DVT channel lives on one dedicated OS thread.
//! No pairing data, coordinates, or upstream errors are logged.

use idevice::{
    IdeviceError, RsdService,
    dvt::{location_simulation::LocationSimulationClient, remote_server::RemoteServerClient},
    remote_pairing::{RemotePairingClient, RpPairingFile, RpPairingSocket},
    rsd::RsdHandshake,
};
use sha2::{Digest, Sha256};
use std::{
    ffi::{CStr, c_char},
    future::Future,
    net::{IpAddr, SocketAddr},
    sync::mpsc::{SyncSender, sync_channel},
    thread::JoinHandle,
    time::Duration,
};
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender, unbounded_channel};
use zeroize::Zeroizing;

mod tls_tunnel;

const OK: i32 = 0;
const INVALID_ARGUMENT: i32 = 1;
const INVALID_PAIRING: i32 = 2;
const NOT_CONNECTED: i32 = 3;
const CONNECTION_FAILED: i32 = 4;
const WRITE_FAILED: i32 = 5;
const CLEAR_FAILED: i32 = 6;
const TIMED_OUT: i32 = 7;
const ALREADY_CONNECTED: i32 = 8;
const WORKER_UNAVAILABLE: i32 = 9;
const PAIRING_REJECTED: i32 = 10;
const DEVELOPER_SERVICE_UNAVAILABLE: i32 = 11;
const OPERATION_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_PAIRING_BYTES: usize = 1024 * 1024;

// No Debug derive: a pairing record contains private keys.
struct Provider {
    address: IpAddr,
    remote_pairing_port: u16,
    pairing: RpPairingFile,
    pairing_digest: [u8; 32],
}

impl std::fmt::Debug for Provider {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Mirage device provider [private]")
    }
}

/// Parse only the explicitly imported Remote Pairing schema. The upstream parser
/// tolerates an incorrectly typed alt_irk and logs its dictionary, so validate
/// directly and erase the temporary private-key Data buffer on every exit.
fn parse_pairing(bytes: &[u8]) -> Result<RpPairingFile, i32> {
    if bytes.is_empty() || bytes.len() > MAX_PAIRING_BYTES {
        return Err(INVALID_PAIRING);
    }
    let mut values: plist::Dictionary = plist::from_bytes(bytes).map_err(|_| INVALID_PAIRING)?;
    let private_bytes = Zeroizing::new(
        values
            .remove("private_key")
            .and_then(|v| v.into_data())
            .ok_or(INVALID_PAIRING)?,
    );
    if ["HostID", "SystemBUID", "HostCertificate", "HostPrivateKey"]
        .iter()
        .any(|key| values.contains_key(*key))
    {
        return Err(INVALID_PAIRING);
    }
    let private_seed: &[u8; 32] = private_bytes
        .as_slice()
        .try_into()
        .map_err(|_| INVALID_PAIRING)?;
    let e_private_key = ed25519_dalek::SigningKey::from_bytes(private_seed);
    let public_bytes = values
        .remove("public_key")
        .and_then(|v| v.into_data())
        .ok_or(INVALID_PAIRING)?;
    let public_bytes: &[u8; 32] = public_bytes
        .as_slice()
        .try_into()
        .map_err(|_| INVALID_PAIRING)?;
    let e_public_key =
        ed25519_dalek::VerifyingKey::from_bytes(public_bytes).map_err(|_| INVALID_PAIRING)?;
    if e_private_key.verifying_key() != e_public_key {
        return Err(INVALID_PAIRING);
    }
    let identifier = values
        .remove("identifier")
        .and_then(|v| v.into_string())
        .ok_or(INVALID_PAIRING)?;
    if identifier.trim().is_empty() || identifier.len() > 256 {
        return Err(INVALID_PAIRING);
    }
    let alt_irk = match values.remove("alt_irk") {
        None => None,
        Some(plist::Value::Data(bytes)) if bytes.len() == 16 => Some(bytes),
        Some(_) => return Err(INVALID_PAIRING),
    };
    Ok(RpPairingFile {
        e_private_key,
        e_public_key,
        identifier,
        alt_irk,
    })
}

enum Command {
    Connect {
        address: IpAddr,
        port: u16,
        pairing: Zeroizing<Vec<u8>>,
    },
    Set {
        latitude: f64,
        longitude: f64,
    },
    Clear,
    Shutdown,
}

struct Request {
    command: Command,
    reply: SyncSender<i32>,
}

/// Opaque ABI object. Callers may submit commands from any thread; no idevice
/// object ever leaves the worker. Destruction requires exclusive ownership.
pub struct MirageDeviceBridge {
    sender: UnboundedSender<Request>,
    thread: Option<JoinHandle<()>>,
}

impl MirageDeviceBridge {
    fn request(&self, command: Command) -> i32 {
        let (reply, receiver) = sync_channel(1);
        if self.sender.send(Request { command, reply }).is_err() {
            return WORKER_UNAVAILABLE;
        }
        receiver.recv().unwrap_or(WORKER_UNAVAILABLE)
    }
}

async fn bounded<T>(
    operation: impl Future<Output = Result<T, IdeviceError>>,
    failure: i32,
) -> Result<T, i32> {
    match tokio::time::timeout(OPERATION_TIMEOUT, operation).await {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(_)) => Err(failure),
        Err(_) => Err(TIMED_OUT),
    }
}

async fn bounded_status<T>(operation: impl Future<Output = Result<T, i32>>) -> Result<T, i32> {
    tokio::time::timeout(OPERATION_TIMEOUT, operation)
        .await
        .unwrap_or(Err(TIMED_OUT))
}

fn service_failure(error: IdeviceError) -> i32 {
    match error {
        IdeviceError::ServiceNotFound
        | IdeviceError::DeveloperModeNotEnabled
        | IdeviceError::ImageNotMounted => DEVELOPER_SERVICE_UNAVAILABLE,
        _ => CONNECTION_FAILED,
    }
}

/// The remote server and its borrowed LocationSimulationClient intentionally
/// remain local to this async scope. A failed/expired operation drops the entire
/// connection; reusing a partially consumed DVT response would be unsafe.
async fn connected(
    provider: &Provider,
    initial: Request,
    receiver: &mut UnboundedReceiver<Request>,
) -> bool {
    let transport = bounded_status(async {
        let endpoint = SocketAddr::new(provider.address, provider.remote_pairing_port);
        let stream = tokio::net::TcpStream::connect(endpoint)
            .await
            .map_err(|_| CONNECTION_FAILED)?;
        let mut client = RemotePairingClient::new(RpPairingSocket::new(stream), "Mirage");
        client
            .attempt_pair_verify()
            .await
            .map_err(|_| CONNECTION_FAILED)?;
        // Deliberately do not call connect(): it silently falls back to PIN setup.
        let mut credentials = provider.pairing.clone();
        client
            .validate_pairing(&mut credentials)
            .await
            .map_err(|_| PAIRING_REJECTED)?;
        let tunnel_port = client
            .create_tcp_listener()
            .await
            .map_err(|_| CONNECTION_FAILED)?;
        if tunnel_port == 0 {
            return Err(CONNECTION_FAILED);
        }
        let tunnel_stream =
            tokio::net::TcpStream::connect(SocketAddr::new(provider.address, tunnel_port))
                .await
                .map_err(|_| CONNECTION_FAILED)?;
        let (tunnel, info) = tls_tunnel::connect(tunnel_stream, client.encryption_key()).await?;
        let rsd_port = info.rsd_port;
        let mut adapter =
            idevice::tcp::adapter::Adapter::new(Box::new(tunnel), info.client_ip, info.server_ip);
        adapter.set_mss(info.mtu.min(8192) - 60);
        Ok((adapter.to_async_handle(), rsd_port))
    })
    .await;
    let (mut adapter, rsd_port) = match transport {
        Ok(transport) => transport,
        Err(status) => {
            let _ = initial.reply.send(status);
            return false;
        }
    };
    let connection = bounded_status(async {
        let stream = adapter
            .connect(rsd_port)
            .await
            .map_err(|_| CONNECTION_FAILED)?;
        let mut handshake = RsdHandshake::new(stream)
            .await
            .map_err(|_| CONNECTION_FAILED)?;
        let mut server = RemoteServerClient::connect_rsd(&mut adapter, &mut handshake)
            .await
            .map_err(service_failure)?;
        server.read_message(0).await.map_err(service_failure)?;
        Ok(server)
    })
    .await;
    let mut server = match connection {
        Ok(server) => server,
        Err(status) => {
            let _ = initial.reply.send(status);
            let _ = tokio::time::timeout(OPERATION_TIMEOUT, adapter.close()).await;
            return false;
        }
    };
    let channel = bounded(
        LocationSimulationClient::new(&mut server),
        CONNECTION_FAILED,
    )
    .await;
    let mut location = match channel {
        Ok(channel) => channel,
        Err(status) => {
            let _ = initial.reply.send(status);
            let _ = tokio::time::timeout(OPERATION_TIMEOUT, adapter.close()).await;
            return false;
        }
    };

    // A clear after failure/relaunch reconnects and clears the device. It never
    // sends a position first and never silently resumes the previous route.
    if matches!(initial.command, Command::Clear) {
        let result = bounded(location.clear(), CLEAR_FAILED).await;
        let status = result.as_ref().err().copied().unwrap_or(OK);
        let _ = initial.reply.send(status);
        if result.is_err() {
            drop(location);
            drop(server);
            let _ = tokio::time::timeout(OPERATION_TIMEOUT, adapter.close()).await;
            return false;
        }
    } else {
        let _ = initial.reply.send(OK);
    }

    let shutdown = loop {
        let Some(request) = receiver.recv().await else {
            break true;
        };
        let (status, failed) = match request.command {
            Command::Set {
                latitude,
                longitude,
            } => {
                let result = bounded(location.set(latitude, longitude), WRITE_FAILED).await;
                (
                    result.as_ref().err().copied().unwrap_or(OK),
                    result.is_err(),
                )
            }
            Command::Clear => {
                let result = bounded(location.clear(), CLEAR_FAILED).await;
                (
                    result.as_ref().err().copied().unwrap_or(OK),
                    result.is_err(),
                )
            }
            Command::Connect {
                address,
                port,
                pairing,
            } => {
                // Same configuration can be re-used after a successful Stop.
                // Replacing credentials/endpoint cannot interrupt an active session.
                let same = address == provider.address
                    && port == provider.remote_pairing_port
                    && <[u8; 32]>::from(Sha256::digest(&*pairing)) == provider.pairing_digest;
                (if same { OK } else { ALREADY_CONNECTED }, false)
            }
            Command::Shutdown => {
                // Best effort only. The app must retain its recovery marker;
                // destruction does not prove restoration of real location.
                let status = bounded(location.clear(), CLEAR_FAILED)
                    .await
                    .err()
                    .unwrap_or(OK);
                let _ = request.reply.send(status);
                break true;
            }
        };
        let _ = request.reply.send(status);
        if failed {
            break false;
        }
    };
    drop(location);
    drop(server);
    let _ = tokio::time::timeout(OPERATION_TIMEOUT, adapter.close()).await;
    shutdown
}

async fn worker(mut receiver: UnboundedReceiver<Request>) {
    let mut provider: Option<Provider> = None;
    while let Some(request) = receiver.recv().await {
        let Request { command, reply } = request;
        match command {
            Command::Connect {
                address,
                port,
                pairing,
            } => {
                match parse_pairing(&pairing) {
                    Ok(parsed) => {
                        provider = Some(Provider {
                            address,
                            remote_pairing_port: port,
                            pairing: parsed,
                            pairing_digest: Sha256::digest(&*pairing).into(),
                        })
                    }
                    Err(_) => {
                        let _ = reply.send(INVALID_PAIRING);
                        continue;
                    }
                }
                // Erase the temporary serialized credential before networking.
                drop(pairing);
                let initial = Request {
                    command: Command::Connect {
                        address,
                        port,
                        pairing: Zeroizing::new(Vec::new()),
                    },
                    reply,
                };
                if connected(provider.as_ref().unwrap(), initial, &mut receiver).await {
                    return;
                }
            }
            Command::Clear => {
                if let Some(provider) = provider.as_ref() {
                    if connected(
                        provider,
                        Request {
                            command: Command::Clear,
                            reply,
                        },
                        &mut receiver,
                    )
                    .await
                    {
                        return;
                    }
                } else {
                    let _ = reply.send(NOT_CONNECTED);
                }
            }
            Command::Set { .. } => {
                let _ = reply.send(NOT_CONNECTED);
            }
            Command::Shutdown => {
                let _ = reply.send(OK);
                return;
            }
        }
    }
}

fn valid_coordinate(latitude: f64, longitude: f64) -> bool {
    latitude.is_finite()
        && longitude.is_finite()
        && (-90.0..=90.0).contains(&latitude)
        && (-180.0..=180.0).contains(&longitude)
}

#[unsafe(no_mangle)]
pub extern "C" fn mirage_device_create() -> *mut MirageDeviceBridge {
    let (sender, receiver) = unbounded_channel();
    let thread = match std::thread::Builder::new()
        .name("MirageDeviceTransport".into())
        .spawn(move || {
            let Ok(runtime) = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            else {
                return;
            };
            runtime.block_on(worker(receiver));
        }) {
        Ok(thread) => thread,
        Err(_) => return std::ptr::null_mut(),
    };
    Box::into_raw(Box::new(MirageDeviceBridge {
        sender,
        thread: Some(thread),
    }))
}

/// # Safety
/// All pointers must remain valid for this call; host must be NUL terminated.
/// Pairing bytes are copied into zeroizing memory, never retained by pointer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mirage_device_connect(
    handle: *mut MirageDeviceBridge,
    host: *const c_char,
    remote_pairing_port: u16,
    pairing_bytes: *const u8,
    pairing_size: usize,
) -> i32 {
    if handle.is_null()
        || host.is_null()
        || pairing_bytes.is_null()
        || pairing_size == 0
        || pairing_size > MAX_PAIRING_BYTES
        || remote_pairing_port == 0
    {
        return INVALID_ARGUMENT;
    }
    let address: IpAddr = match unsafe { CStr::from_ptr(host) }
        .to_str()
        .ok()
        .and_then(|s| s.parse().ok())
    {
        Some(address) => address,
        None => return INVALID_ARGUMENT,
    };
    let pairing =
        Zeroizing::new(unsafe { std::slice::from_raw_parts(pairing_bytes, pairing_size) }.to_vec());
    unsafe { &*handle }.request(Command::Connect {
        address,
        port: remote_pairing_port,
        pairing,
    })
}

/// # Safety
/// handle must be live and cannot be destroyed during this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mirage_device_set(
    handle: *mut MirageDeviceBridge,
    latitude: f64,
    longitude: f64,
) -> i32 {
    if handle.is_null() || !valid_coordinate(latitude, longitude) {
        return INVALID_ARGUMENT;
    }
    unsafe { &*handle }.request(Command::Set {
        latitude,
        longitude,
    })
}

/// # Safety
/// handle must be live and cannot be destroyed during this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mirage_device_clear(handle: *mut MirageDeviceBridge) -> i32 {
    if handle.is_null() {
        return INVALID_ARGUMENT;
    }
    unsafe { &*handle }.request(Command::Clear)
}

/// # Safety
/// Exclusive ownership is required. Do not use the handle after this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mirage_device_destroy(handle: *mut MirageDeviceBridge) {
    if handle.is_null() {
        return;
    }
    let mut bridge = unsafe { Box::from_raw(handle) };
    bridge.request(Command::Shutdown);
    if let Some(thread) = bridge.thread.take() {
        let _ = thread.join();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn synthetic_pairing() -> plist::Dictionary {
        let private = ed25519_dalek::SigningKey::from_bytes(&[7; 32]);
        let mut values = plist::Dictionary::new();
        values.insert(
            "private_key".into(),
            plist::Value::Data(private.to_bytes().to_vec()),
        );
        values.insert(
            "public_key".into(),
            plist::Value::Data(private.verifying_key().to_bytes().to_vec()),
        );
        values.insert(
            "identifier".into(),
            plist::Value::String("MirageSyntheticFixture".into()),
        );
        values
    }

    fn xml(values: plist::Dictionary) -> Zeroizing<Vec<u8>> {
        let mut bytes = Zeroizing::new(Vec::new());
        plist::Value::Dictionary(values)
            .to_writer_xml(&mut *bytes)
            .unwrap();
        bytes
    }

    #[test]
    fn accepts_remote_pairing_xml_and_binary_with_matching_keys() {
        let mut values = synthetic_pairing();
        values.insert("alt_irk".into(), plist::Value::Data(vec![3; 16]));
        let serialized = xml(values.clone());
        let parsed = parse_pairing(&serialized).unwrap();
        assert_eq!(parsed.identifier, "MirageSyntheticFixture");
        assert_eq!(parsed.alt_irk.as_ref().map(Vec::len), Some(16));
        let mut binary = Zeroizing::new(Vec::new());
        plist::Value::Dictionary(values)
            .to_writer_binary(&mut *binary)
            .unwrap();
        assert!(parse_pairing(&binary).is_ok());
        assert!(parse_pairing(&xml(synthetic_pairing())).is_ok());
    }

    #[test]
    fn rejects_missing_wrong_type_and_wrong_length_credentials() {
        for key in ["public_key", "private_key", "identifier"] {
            let mut values = synthetic_pairing();
            values.remove(key);
            assert!(matches!(parse_pairing(&xml(values)), Err(INVALID_PAIRING)));
        }
        for key in ["public_key", "private_key"] {
            for value in [
                plist::Value::Data(vec![0; 31]),
                plist::Value::Data(vec![0; 33]),
                plist::Value::String("not-data".into()),
            ] {
                let mut values = synthetic_pairing();
                values.insert(key.into(), value);
                assert!(matches!(parse_pairing(&xml(values)), Err(INVALID_PAIRING)));
            }
        }
        for value in [
            plist::Value::String(" \t\n ".into()),
            plist::Value::String("x".repeat(257)),
            plist::Value::String("é".repeat(129)),
            plist::Value::Data(vec![1]),
        ] {
            let mut values = synthetic_pairing();
            values.insert("identifier".into(), value);
            assert!(matches!(parse_pairing(&xml(values)), Err(INVALID_PAIRING)));
        }
        for value in [
            plist::Value::Data(vec![0; 15]),
            plist::Value::Data(vec![0; 17]),
            plist::Value::String("not-data".into()),
        ] {
            let mut values = synthetic_pairing();
            values.insert("alt_irk".into(), value);
            assert!(matches!(parse_pairing(&xml(values)), Err(INVALID_PAIRING)));
        }
    }

    #[test]
    fn rejects_mismatched_keys_and_classic_or_mixed_records() {
        let mut values = synthetic_pairing();
        let another = ed25519_dalek::SigningKey::from_bytes(&[8; 32]);
        values.insert(
            "public_key".into(),
            plist::Value::Data(another.verifying_key().to_bytes().to_vec()),
        );
        assert!(matches!(parse_pairing(&xml(values)), Err(INVALID_PAIRING)));
        for key in ["HostID", "SystemBUID", "HostCertificate", "HostPrivateKey"] {
            let mut values = synthetic_pairing();
            values.insert(
                key.into(),
                plist::Value::String("synthetic-classic-field".into()),
            );
            assert!(matches!(parse_pairing(&xml(values)), Err(INVALID_PAIRING)));
            let mut classic = plist::Dictionary::new();
            classic.insert(
                key.into(),
                plist::Value::String("synthetic-classic-field".into()),
            );
            assert!(matches!(parse_pairing(&xml(classic)), Err(INVALID_PAIRING)));
        }
    }

    #[test]
    fn tracing_is_compiled_out_including_credential_format_arguments() {
        assert_eq!(
            tracing::level_filters::STATIC_MAX_LEVEL,
            tracing::level_filters::LevelFilter::OFF
        );
        let evaluated = std::cell::Cell::new(false);
        tracing::error!(
            credential = {
                evaluated.set(true);
                "synthetic-private-data"
            }
        );
        assert!(!evaluated.get());
    }

    #[test]
    fn developer_prerequisite_errors_have_fixed_actionable_status() {
        assert_eq!(
            service_failure(IdeviceError::ServiceNotFound),
            DEVELOPER_SERVICE_UNAVAILABLE
        );
        assert_eq!(
            service_failure(IdeviceError::DeveloperModeNotEnabled),
            DEVELOPER_SERVICE_UNAVAILABLE
        );
        assert_eq!(
            service_failure(IdeviceError::ImageNotMounted),
            DEVELOPER_SERVICE_UNAVAILABLE
        );
        assert_eq!(
            service_failure(IdeviceError::NoEstablishedConnection),
            CONNECTION_FAILED
        );
    }

    #[test]
    fn validates_coordinates_before_transport() {
        for (lat, lon) in [
            (f64::NAN, 0.0),
            (0.0, f64::INFINITY),
            (90.01, 0.0),
            (0.0, -180.01),
        ] {
            assert!(!valid_coordinate(lat, lon));
        }
        assert!(valid_coordinate(-90.0, 180.0));
    }

    #[test]
    fn disconnected_commands_cannot_succeed() {
        let handle = mirage_device_create();
        assert!(!handle.is_null());
        unsafe {
            assert_eq!(mirage_device_set(handle, 32.0, -110.0), NOT_CONNECTED);
            assert_eq!(mirage_device_clear(handle), NOT_CONNECTED);
            mirage_device_destroy(handle);
        }
    }

    #[test]
    fn malformed_pairing_is_rejected_without_networking() {
        let handle = mirage_device_create();
        assert!(!handle.is_null());
        let invalid = b"not a pairing record";
        unsafe {
            assert_eq!(
                mirage_device_connect(
                    handle,
                    c"127.0.0.1".as_ptr(),
                    49152,
                    invalid.as_ptr(),
                    invalid.len()
                ),
                INVALID_PAIRING
            );
            assert_eq!(mirage_device_clear(handle), NOT_CONNECTED);
            mirage_device_destroy(handle);
        }
    }

    #[test]
    fn null_handles_and_out_of_range_requests_are_rejected() {
        unsafe {
            assert_eq!(
                mirage_device_set(std::ptr::null_mut(), 0.0, 0.0),
                INVALID_ARGUMENT
            );
            assert_eq!(mirage_device_clear(std::ptr::null_mut()), INVALID_ARGUMENT);
            mirage_device_destroy(std::ptr::null_mut());
        }
    }
}

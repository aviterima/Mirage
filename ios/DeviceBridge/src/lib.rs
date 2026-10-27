//! Original Mirage adapter over the MIT-licensed, revision-pinned idevice library.
//! Every connection and borrowed DVT channel lives on one dedicated OS thread.
//! No pairing data, coordinates, or upstream errors are logged.

use idevice::{
    Idevice, IdeviceError, IdeviceService, RsdService,
    core_device_proxy::CoreDeviceProxy,
    dvt::{location_simulation::LocationSimulationClient, remote_server::RemoteServerClient},
    pairing_file::PairingFile,
    provider::IdeviceProvider,
    rsd::RsdHandshake,
};
use sha2::{Digest, Sha256};
use std::{
    ffi::{CStr, c_char},
    future::Future,
    net::{IpAddr, SocketAddr},
    pin::Pin,
    sync::mpsc::{SyncSender, sync_channel},
    thread::JoinHandle,
    time::Duration,
};
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender, unbounded_channel};
use zeroize::Zeroizing;

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
const OPERATION_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_PAIRING_BYTES: usize = 1024 * 1024;

// No Debug derive: a pairing record contains private keys.
struct Provider {
    address: IpAddr,
    lockdown_port: u16,
    pairing: PairingFile,
    pairing_digest: [u8; 32],
}

impl std::fmt::Debug for Provider {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Mirage device provider [private]")
    }
}

impl IdeviceProvider for Provider {
    fn connect(
        &self,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = Result<Idevice, IdeviceError>> + Send>> {
        let port = if port == 62078 {
            self.lockdown_port
        } else {
            port
        };
        let address = SocketAddr::new(self.address, port);
        Box::pin(async move {
            let stream = tokio::net::TcpStream::connect(address).await?;
            Ok(Idevice::new(Box::new(stream), "Mirage"))
        })
    }

    fn label(&self) -> &str {
        "Mirage"
    }

    fn get_pairing_file(
        &self,
    ) -> Pin<Box<dyn Future<Output = Result<PairingFile, IdeviceError>> + Send>> {
        let pairing = self.pairing.clone();
        Box::pin(async move { Ok(pairing) })
    }
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

/// The remote server and its borrowed LocationSimulationClient intentionally
/// remain local to this async scope. A failed/expired operation drops the entire
/// connection; reusing a partially consumed DVT response would be unsafe.
async fn connected(
    provider: &Provider,
    initial: Request,
    receiver: &mut UnboundedReceiver<Request>,
) -> bool {
    let connection = bounded(
        async {
            let proxy = CoreDeviceProxy::connect(provider).await?;
            let port = proxy.tunnel_info().server_rsd_port;
            let mut adapter = proxy.create_software_tunnel()?.to_async_handle();
            let stream = adapter.connect(port).await?;
            let mut handshake = RsdHandshake::new(stream).await?;
            let mut server = RemoteServerClient::connect_rsd(&mut adapter, &mut handshake).await?;
            server.read_message(0).await?;
            Ok((adapter, server))
        },
        CONNECTION_FAILED,
    )
    .await;

    let (mut adapter, mut server) = match connection {
        Ok(connection) => connection,
        Err(status) => {
            let _ = initial.reply.send(status);
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
                    && port == provider.lockdown_port
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
                match PairingFile::from_bytes(&pairing) {
                    Ok(parsed) => {
                        provider = Some(Provider {
                            address,
                            lockdown_port: port,
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
    port: u16,
    pairing_bytes: *const u8,
    pairing_size: usize,
) -> i32 {
    if handle.is_null()
        || host.is_null()
        || pairing_bytes.is_null()
        || pairing_size == 0
        || pairing_size > MAX_PAIRING_BYTES
        || port == 0
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
        port,
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
                    62078,
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

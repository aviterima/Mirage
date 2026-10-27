//! TLS-PSK uses OpenSSL's authenticated TLS 1.2 implementation. We deliberately
//! bypass idevice's native TLS helper, which accepts a mismatched Finished and
//! has unchecked CBC padding/CDTunnel slices at the pinned revision.
//!
//! Protocol configuration follows the MIT-licensed idevice implementation by
//! Jackson Coxson at 26a28f6a46b5d85a28043c2e62febfa394c8b0a6, remote_pairing/tunnel.rs.
//! Its license and the vendored OpenSSL notices are in THIRD-PARTY-NOTICES.txt.

use crate::CONNECTION_FAILED;
use openssl::ssl::{SslConnector, SslMethod, SslOptions, SslVerifyMode, SslVersion};
use std::{net::IpAddr, pin::Pin};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio_openssl::SslStream;
use zeroize::Zeroizing;

const PSK_BYTES: usize = 32;
const MAGIC: &[u8; 8] = b"CDTunnel";
const MAX_HANDSHAKE_BYTES: usize = 16 * 1024;
const CIPHERS: &str =
    "PSK-AES256-CBC-SHA384:PSK-AES128-CBC-SHA256:PSK-AES256-CBC-SHA:PSK-AES128-CBC-SHA";
const REQUEST: &[u8] = br#"{"type":"clientHandshakeRequest","mtu":16000}"#;

pub(super) struct TunnelInfo {
    pub client_ip: IpAddr,
    pub server_ip: IpAddr,
    pub mtu: usize,
    pub rsd_port: u16,
}

fn provide_psk(key: &[u8], identity: &mut [u8], output: &mut [u8]) -> usize {
    if key.len() != PSK_BYTES || identity.is_empty() || output.len() < PSK_BYTES {
        return 0; // OpenSSL aborts the handshake; never truncate a secret.
    }
    identity[0] = 0; // Remote Pairing uses an empty PSK identity.
    output[..PSK_BYTES].copy_from_slice(key);
    PSK_BYTES
}

async fn authenticated_tls<S>(stream: S, key: &[u8]) -> Result<SslStream<S>, i32>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    if key.len() != PSK_BYTES {
        return Err(CONNECTION_FAILED);
    }
    let key = Zeroizing::new(key.to_vec());
    let mut builder =
        SslConnector::builder(SslMethod::tls_client()).map_err(|_| CONNECTION_FAILED)?;
    // No X.509 certificate is exchanged by these PSK-only suites. Peer
    // authentication is OpenSSL's mandatory MAC/Finished verification using the
    // secret established by Remote Pairing. There is no certificate-suite fallback.
    builder.set_verify(SslVerifyMode::NONE);
    builder
        .set_cipher_list(CIPHERS)
        .map_err(|_| CONNECTION_FAILED)?;
    builder
        .set_min_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|_| CONNECTION_FAILED)?;
    builder
        .set_max_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|_| CONNECTION_FAILED)?;
    builder.set_options(
        SslOptions::NO_RENEGOTIATION | SslOptions::NO_TICKET | SslOptions::NO_COMPRESSION,
    );
    builder.set_psk_client_callback(move |_ssl, _hint, identity, output| {
        Ok(provide_psk(&key, identity, output))
    });
    let connector = builder.build();
    let mut configuration = connector.configure().map_err(|_| CONNECTION_FAILED)?;
    configuration.set_verify_hostname(false);
    configuration.set_use_server_name_indication(false);
    let ssl = configuration
        .into_ssl("localhost")
        .map_err(|_| CONNECTION_FAILED)?;
    let mut tls = SslStream::new(ssl, stream).map_err(|_| CONNECTION_FAILED)?;
    Pin::new(&mut tls)
        .connect()
        .await
        .map_err(|_| CONNECTION_FAILED)?;
    Ok(tls)
}

fn decode_info(body: &[u8]) -> Result<TunnelInfo, i32> {
    let response: serde_json::Value =
        serde_json::from_slice(body).map_err(|_| CONNECTION_FAILED)?;
    if response.get("type").and_then(|v| v.as_str()) != Some("serverHandshakeResponse") {
        return Err(CONNECTION_FAILED);
    }
    let client = response
        .get("clientParameters")
        .and_then(|v| v.as_object())
        .ok_or(CONNECTION_FAILED)?;
    let client_ip = client
        .get("address")
        .and_then(|v| v.as_str())
        .and_then(|s| s.parse::<IpAddr>().ok())
        .ok_or(CONNECTION_FAILED)?;
    let server_ip = response
        .get("serverAddress")
        .and_then(|v| v.as_str())
        .and_then(|s| s.parse::<IpAddr>().ok())
        .ok_or(CONNECTION_FAILED)?;
    // The software tunnel carries IPv6. Reject impossible or ambiguous endpoints.
    if !client_ip.is_ipv6()
        || !server_ip.is_ipv6()
        || client_ip == server_ip
        || client_ip.is_unspecified()
        || server_ip.is_unspecified()
        || client_ip.is_multicast()
        || server_ip.is_multicast()
    {
        return Err(CONNECTION_FAILED);
    }
    let mtu = client
        .get("mtu")
        .and_then(|v| v.as_u64())
        .ok_or(CONNECTION_FAILED)?;
    let port = response
        .get("serverRSDPort")
        .and_then(|v| v.as_u64())
        .ok_or(CONNECTION_FAILED)?;
    if !(1280..=16000).contains(&mtu) || !(1..=u16::MAX as u64).contains(&port) {
        return Err(CONNECTION_FAILED);
    }
    Ok(TunnelInfo {
        client_ip,
        server_ip,
        mtu: mtu as usize,
        rsd_port: port as u16,
    })
}

async fn read_info<S: AsyncRead + Unpin>(stream: &mut S) -> Result<TunnelInfo, i32> {
    let mut header = [0u8; 10];
    stream
        .read_exact(&mut header)
        .await
        .map_err(|_| CONNECTION_FAILED)?;
    if &header[..8] != MAGIC {
        return Err(CONNECTION_FAILED);
    }
    let length = u16::from_be_bytes([header[8], header[9]]) as usize;
    if length == 0 || length > MAX_HANDSHAKE_BYTES {
        return Err(CONNECTION_FAILED);
    }
    let mut body = vec![0u8; length];
    stream
        .read_exact(&mut body)
        .await
        .map_err(|_| CONNECTION_FAILED)?;
    decode_info(&body)
}

pub(super) async fn connect<S>(stream: S, key: &[u8]) -> Result<(SslStream<S>, TunnelInfo), i32>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut tls = authenticated_tls(stream, key).await?;
    tls.write_all(MAGIC).await.map_err(|_| CONNECTION_FAILED)?;
    tls.write_all(&(REQUEST.len() as u16).to_be_bytes())
        .await
        .map_err(|_| CONNECTION_FAILED)?;
    tls.write_all(REQUEST)
        .await
        .map_err(|_| CONNECTION_FAILED)?;
    tls.flush().await.map_err(|_| CONNECTION_FAILED)?;
    let info = read_info(&mut tls).await?;
    Ok((tls, info))
}

#[cfg(test)]
mod tests {
    use super::*;
    use openssl::ssl::{Ssl, SslContextBuilder};
    use std::time::Duration;

    fn valid_response() -> serde_json::Value {
        serde_json::json!({"type": "serverHandshakeResponse", "clientParameters": {
            "address": "fd00::1", "mtu": 16000 }, "serverAddress": "fd00::2", "serverRSDPort": 12345 })
    }

    fn frame(body: &[u8]) -> Vec<u8> {
        let mut data = MAGIC.to_vec();
        data.extend_from_slice(&(body.len() as u16).to_be_bytes());
        data.extend_from_slice(body);
        data
    }

    #[test]
    fn psk_callback_never_truncates_or_accepts_wrong_key_lengths() {
        let mut identity = [9; 1];
        let mut output = [0; PSK_BYTES];
        assert_eq!(
            provide_psk(&[7; PSK_BYTES], &mut identity, &mut output),
            PSK_BYTES
        );
        assert_eq!(identity, [0]);
        assert_eq!(output, [7; PSK_BYTES]);
        assert_eq!(provide_psk(&[7; 31], &mut identity, &mut output), 0);
        assert_eq!(provide_psk(&[7; 33], &mut identity, &mut output), 0);
        assert_eq!(provide_psk(&[7; PSK_BYTES], &mut [], &mut output), 0);
        assert_eq!(provide_psk(&[7; PSK_BYTES], &mut identity, &mut [0; 31]), 0);
    }

    #[tokio::test]
    async fn malformed_tunnel_frames_fail_without_panics_or_large_allocations() {
        let good = frame(&serde_json::to_vec(&valid_response()).unwrap());
        for end in 0..good.len() {
            assert!(read_info(&mut &good[..end]).await.is_err());
        }
        for bytes in [
            b"wrongmagic".to_vec(),
            [MAGIC.as_slice(), &[0, 0]].concat(),
            [MAGIC.as_slice(), &u16::MAX.to_be_bytes()].concat(),
            frame(b"not-json"),
        ] {
            assert!(read_info(&mut bytes.as_slice()).await.is_err());
        }
        let info = read_info(&mut good.as_slice()).await.unwrap();
        assert_eq!(info.rsd_port, 12345);
    }

    #[test]
    fn invalid_handshake_types_addresses_mtu_and_ports_fail_closed() {
        let mut mutations = Vec::new();
        for value in [
            serde_json::json!(0),
            serde_json::json!(65536),
            serde_json::json!(-1),
            serde_json::json!("12345"),
        ] {
            let mut response = valid_response();
            response["serverRSDPort"] = value;
            mutations.push(response);
        }
        for value in [
            serde_json::json!(60),
            serde_json::json!(1279),
            serde_json::json!(16001),
            serde_json::json!(u64::MAX),
        ] {
            let mut response = valid_response();
            response["clientParameters"]["mtu"] = value;
            mutations.push(response);
        }
        for value in ["not-an-address", "127.0.0.1", "::", "ff02::1", "fd00::1"] {
            let mut response = valid_response();
            response["serverAddress"] = serde_json::json!(value);
            mutations.push(response);
        }
        let mut wrong_type = valid_response();
        wrong_type["type"] = serde_json::json!("clientHandshakeRequest");
        mutations.push(wrong_type);
        mutations.push(serde_json::json!({}));
        for value in mutations {
            assert!(decode_info(&serde_json::to_vec(&value).unwrap()).is_err());
        }
    }

    async fn synthetic_server<S: AsyncRead + AsyncWrite + Unpin>(
        stream: S,
        key: [u8; PSK_BYTES],
    ) -> Result<SslStream<S>, ()> {
        let mut context = SslContextBuilder::new(SslMethod::tls_server()).unwrap();
        context.set_cipher_list(CIPHERS).unwrap();
        context
            .set_min_proto_version(Some(SslVersion::TLS1_2))
            .unwrap();
        context
            .set_max_proto_version(Some(SslVersion::TLS1_2))
            .unwrap();
        context.set_psk_server_callback(move |_ssl, identity, output| {
            if identity != Some(b"".as_slice()) || output.len() < key.len() {
                return Ok(0);
            }
            output[..key.len()].copy_from_slice(&key);
            Ok(key.len())
        });
        let ssl = Ssl::new(&context.build()).unwrap();
        let mut tls = SslStream::new(ssl, stream).unwrap();
        Pin::new(&mut tls).accept().await.map_err(|_| ())?;
        Ok(tls)
    }

    #[tokio::test]
    async fn tls_authenticates_matching_psk_and_rejects_a_different_psk() {
        for matching in [true, false] {
            let (client, server) = tokio::io::duplex(16384);
            let server_key = if matching {
                [7; PSK_BYTES]
            } else {
                [8; PSK_BYTES]
            };
            let (client_result, server_result) =
                tokio::time::timeout(Duration::from_secs(5), async {
                    tokio::join!(
                        authenticated_tls(client, &[7; PSK_BYTES]),
                        synthetic_server(server, server_key)
                    )
                })
                .await
                .expect("synthetic TLS handshake must finish");
            assert_eq!(client_result.is_ok(), matching);
            assert_eq!(server_result.is_ok(), matching);
        }
    }

    #[tokio::test]
    async fn authenticated_fragmented_tunnel_handshake_reads_exactly_one_frame() {
        let (client, server) = tokio::io::duplex(16384);
        let (client_result, server_result) = tokio::time::timeout(Duration::from_secs(5), async {
            tokio::join!(connect(client, &[7; PSK_BYTES]), async {
                let mut tls = synthetic_server(server, [7; PSK_BYTES]).await.unwrap();
                let mut request = vec![0; 10 + REQUEST.len()];
                tls.read_exact(&mut request).await.unwrap();
                assert_eq!(request, frame(REQUEST));
                let response = frame(&serde_json::to_vec(&valid_response()).unwrap());
                for fragment in response.chunks(3) {
                    tls.write_all(fragment).await.unwrap();
                }
                tls.write_all(b"next-packet").await.unwrap();
                tls.flush().await.unwrap();
                tls
            })
        })
        .await
        .expect("synthetic tunnel handshake must finish");
        let (mut tls, info) = client_result.unwrap();
        assert_eq!(info.mtu, 16000);
        let mut next = [0; 11];
        tls.read_exact(&mut next).await.unwrap();
        assert_eq!(&next, b"next-packet");
        drop(server_result);
    }
}

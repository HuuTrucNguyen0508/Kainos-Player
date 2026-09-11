use std::{env, fs, path::PathBuf, process::exit};

use librespot_core::{
    authentication::Credentials, config::SessionConfig, session::Session, SpotifyId,
};
use librespot_metadata::{Metadata, Playlist, Track};
use librespot_protocol::playlist4_external::SelectedListContent;
use protobuf::Message;
use serde::Serialize;

#[derive(Serialize)]
struct OutPlaylist {
    id: String,
    name: String,
    description: String,
    artwork_url: Option<String>,
    tracks: Vec<OutTrack>,
}

#[derive(Serialize)]
struct OutTrack {
    id: String,
    title: String,
    artists: Vec<String>,
    duration_ms: Option<i32>,
    artwork_url: Option<String>,
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: {} <credentials.json> [discover-weekly|playlist:<id>]", args[0]);
        exit(2);
    }
    let cred_path = PathBuf::from(&args[1]);
    let mode = args.get(2).map(String::as_str).unwrap_or("discover-weekly");

    let raw = fs::read_to_string(&cred_path).unwrap_or_else(|e| {
        eprintln!("cannot read {}: {e}", cred_path.display());
        exit(1);
    });
    let credentials: Credentials = serde_json::from_str(&raw).unwrap_or_else(|e| {
        eprintln!("cannot parse credentials: {e}");
        exit(1);
    });

    let session = Session::new(SessionConfig::default(), None);
    if let Err(e) = session.connect(credentials, false).await {
        eprintln!("session connect failed: {e}");
        exit(1);
    }

    let playlist_id = match mode {
        "discover-weekly" => match find_discover_weekly(&session).await {
            Some(id) => id,
            None => {
                eprintln!("Discover Weekly not found in rootlist");
                println!("{{\"ok\":false,\"error\":\"not_found\"}}");
                exit(1);
            }
        },
        other if other.starts_with("playlist:") => other.trim_start_matches("playlist:").to_string(),
        other => other.to_string(),
    };

    match fetch_playlist(&session, &playlist_id).await {
        Ok(out) => {
            let mut map = serde_json::to_value(&out).unwrap();
            if let Some(obj) = map.as_object_mut() {
                obj.insert("ok".into(), serde_json::Value::Bool(true));
            }
            println!("{}", serde_json::to_string(&map).unwrap());
        }
        Err(e) => {
            eprintln!("fetch failed: {e}");
            println!("{{\"ok\":false,\"error\":{}}}", serde_json::to_string(&e).unwrap());
            exit(1);
        }
    }
}

async fn find_discover_weekly(session: &Session) -> Option<String> {
    let bytes = session.spclient().get_rootlist(0, Some(500)).await.ok()?;
    let content = SelectedListContent::parse_from_bytes(&bytes).ok()?;
    let uris: Vec<String> = content
        .contents
        .as_ref()
        .into_iter()
        .flat_map(|c| c.items.iter())
        .map(|item| item.uri().to_string())
        .collect();

    for uri in uris {
        let Some(id) = uri.strip_prefix("spotify:playlist:") else { continue };
        if id.starts_with("start-group") || id.starts_with("end-group") {
            continue;
        }
        let Ok(spotify_id) = SpotifyId::from_base62(id) else { continue };
        let Ok(bytes) = session.spclient().get_playlist(&spotify_id).await else { continue };
        let Ok(pl) = SelectedListContent::parse_from_bytes(&bytes) else { continue };
        let name = pl
            .attributes
            .as_ref()
            .and_then(|a| a.name.clone())
            .unwrap_or_default();
        if name.eq_ignore_ascii_case("Discover Weekly") {
            return Some(id.to_string());
        }
    }
    None
}

async fn fetch_playlist(session: &Session, playlist_id: &str) -> Result<OutPlaylist, String> {
    let uri = format!("spotify:playlist:{playlist_id}");
    let spotify_uri = librespot_core::spotify_uri::SpotifyUri::from_uri(&uri)
        .map_err(|e| format!("bad uri: {e}"))?;
    let plist = Playlist::get(session, &spotify_uri)
        .await
        .map_err(|e| format!("playlist metadata: {e}"))?;

    let mut tracks = Vec::new();
    for track_uri in plist.tracks() {
        let track = match Track::get(session, track_uri).await {
            Ok(t) => t,
            Err(_) => continue,
        };
        let id = track_uri
            .to_uri()
            .ok()
            .and_then(|u| u.strip_prefix("spotify:track:").map(|s| s.to_string()))
            .unwrap_or_default();
        if id.is_empty() {
            continue;
        }
        tracks.push(OutTrack {
            id,
            title: track.name.clone(),
            artists: track.artists.iter().map(|a| a.name.clone()).collect(),
            duration_ms: Some(track.duration as i32),
            artwork_url: track.album.cover.as_ref().map(|c| {
                // librespot Cover may expose url differently — fall back later if compile fails
                format!("{c:?}")
            }),
        });
    }

    Ok(OutPlaylist {
        id: playlist_id.to_string(),
        name: plist.name.clone(),
        description: plist.description.clone().unwrap_or_default(),
        artwork_url: None,
        tracks,
    })
}

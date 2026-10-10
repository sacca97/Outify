use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
pub struct CreatePlaylistResponse {
    pub id: String,
}

#[derive(Deserialize, Serialize)]
pub struct PlaylistVisibility {
    // Null for a playlist that is neither public nor private
    pub public: Option<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visibility_reads_public_and_private() {
        let public: PlaylistVisibility = serde_json::from_str(r#"{"public":true}"#).unwrap();
        let private: PlaylistVisibility = serde_json::from_str(r#"{"public":false}"#).unwrap();

        assert_eq!(public.public, Some(true));
        assert_eq!(private.public, Some(false));
    }

    #[test]
    fn visibility_of_a_playlist_that_is_neither_is_none() {
        let null: PlaylistVisibility = serde_json::from_str(r#"{"public":null}"#).unwrap();
        let missing: PlaylistVisibility = serde_json::from_str("{}").unwrap();

        assert_eq!(null.public, None);
        assert_eq!(missing.public, None);
    }

    #[test]
    fn visibility_is_handed_to_kotlin_as_json() {
        let json = serde_json::to_string(&PlaylistVisibility { public: None }).unwrap();
        assert_eq!(json, r#"{"public":null}"#);

        let json = serde_json::to_string(&PlaylistVisibility { public: Some(true) }).unwrap();
        assert_eq!(json, r#"{"public":true}"#);
    }
}

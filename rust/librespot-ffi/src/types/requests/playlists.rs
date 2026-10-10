use serde::Serialize;

#[derive(Serialize)]
pub struct AddItemRequest {
    pub uris: Vec<String>,
    pub position: Option<u32>,
}

#[derive(Serialize)]
pub struct RemoveItemRequest {
    pub items: Vec<RemoveItem>,
}

#[derive(Serialize)]
pub struct RemoveItem {
    pub uri: String,
}

#[derive(Serialize)]
pub struct CreatePlaylistRequest {
    pub name: String,
    // Left out to keep the playlist's current visibility
    #[serde(skip_serializing_if = "Option::is_none")]
    pub public: Option<bool>,
    // Public has to be set to false for collaborative to work
    pub collaborative: bool,
    pub description: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(public: Option<bool>) -> serde_json::Value {
        serde_json::to_value(CreatePlaylistRequest {
            name: "Mix".to_string(),
            public,
            collaborative: false,
            description: None,
        })
        .unwrap()
    }

    #[test]
    fn public_is_left_out_to_keep_the_current_visibility() {
        let body = request(None);
        assert!(body.get("public").is_none());
        assert_eq!(body["name"], "Mix");
        assert_eq!(body["collaborative"], false);
    }

    #[test]
    fn public_is_sent_when_set() {
        assert_eq!(request(Some(true))["public"], true);
        assert_eq!(request(Some(false))["public"], false);
    }
}

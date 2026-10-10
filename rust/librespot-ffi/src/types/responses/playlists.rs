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

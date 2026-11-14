package com.prakash.pmeeting.data.model

/** Kind of media in a recording entry. */
enum class MediaType {
    AUDIO,
    VIDEO;

    companion object {
        fun fromNameOrNull(name: String?): MediaType? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Where the bytes of a recording live. */
enum class StorageKind {
    /** A regular file inside the app's private external files directory. */
    INTERNAL_FILE,

    /** A content:// URI picked through the Storage Access Framework. */
    EXTERNAL_URI
}

/** How the recording entered the library. */
enum class RecordingSource {
    RECORDED,
    IMPORTED
}

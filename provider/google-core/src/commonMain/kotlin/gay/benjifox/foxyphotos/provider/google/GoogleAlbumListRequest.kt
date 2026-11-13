package gay.benjifox.foxyphotos.provider.google

/** The read-only library page mask used for native album enumeration. */
object GoogleAlbumListRequest {
    private val template = decodeBase64("Cs0FCqsCClkKABoAIgAqDAoAEgAaACIAKgA6ADIAOgISAHoAggEAigEAmgEAogEAqgEGKgIaADIAygEA8gECEgD6AQCCAgCKAgIKAJICAKICAKoCALICALoCAMICAMoCACpSEhgSChoCEgAiBBIAIgAiBBICEAEqAhIAMAEaGhIEGgAiABoIEgAaBBABGgAiACoEEgIQAToAIgQSAhIAKhQKEBIEGgAiABoIEgAaBBABGgAYAUIASjASABoECgASACImCiQaHAoaCggqAgoAMgA6ABIAGgwKCCoCCgAyADoAEgAiBAoCEgBaDBIAGgAiBhIECAEQAmIAcgwSABoAIgYSBAgBEAJ6BAoAIgCKAQQKACIAmgEMEgAaACIGEgQIARACqgECCgCyAQC6AQDCAQAShQEKKxIAGgAiACoAMgwKABIAGgAiACoAOgA6AEIAUgBiAGoEEgAaAHoCCgCSAQAiAgoASgBaDAoKCgAiACoAMgBKAHIkCiIKHgoAEggSBgoCCgAaABoQIgYKAgoAGgAqBgoCCgAaABIAigEAkgEGCgASAgoAogEGEgQKABIAsgEAugEAwgEAGsgBEgAaYhIAGgA6AEIAcgIKAIIBAIoBAhIAkgEAmgEAogEAqgEAsgEAugEA2gEGCgASAgoA6gEA8gEA+gEAggIAkgIAqgIAsgIAugIAygIA2gICCgDqAgQKAgoA8gIGCgASABoA+gIAIgwSABoCCgAiACoCCgA6AGIAagByHAoAEgwKABICCgAaACICCgAaCgoAEgIKABoAIgB6AIIBAgoAkgEAmgEUIgISADIEEgAaADoEEgAaAEIASgCiAQCyAQDCAQDKAQDSAQAyADgCSioKBhIECgASABIEGgIQARoCEgAiADoCCgBCCggCEgYBAgMFBgdKAFoCCgBYAVgCWAZiDBIECgASABoCCgAiAGoAegQaAggBEgwKCgoGCgIKABIAEgA=")

    fun request(cursor: String?): ByteArray {
        if (cursor == null) return template.copyOf()
        require(cursor.length <= 16384)
        val root = GoogleNativeProtocol.ProtoFields(template)
        return GoogleNativeProtocol.bytesField(1, root.bytes(1)!! +
            GoogleNativeProtocol.bytesField(4, cursor.encodeToByteArray())) +
            GoogleNativeProtocol.bytesField(2, root.bytes(2)!!)
    }

    /** Delta refresh using the library sync token captured by a full listing. */
    fun syncRequest(syncToken: String?): ByteArray {
        if (syncToken.isNullOrEmpty()) return request(null)
        require(syncToken.length <= 16384)
        val root = GoogleNativeProtocol.ProtoFields(template)
        return GoogleNativeProtocol.bytesField(1, root.bytes(1)!! +
            GoogleNativeProtocol.bytesField(6, syncToken.encodeToByteArray())) +
            GoogleNativeProtocol.bytesField(2, root.bytes(2)!!)
    }
}

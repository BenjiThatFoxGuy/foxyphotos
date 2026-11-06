package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.core.model.LibraryItem
import gay.benjifox.foxyphotos.core.model.LibraryPage
import java.util.Base64

/** Wire field mask observed in bclone f6ad6b63, api/protobuf.go; not an account response. */
internal object GoogleLibraryProtocol {
    private val template = Base64.getDecoder().decode("Cs0FCqsCClkKABoAIgAqDAoAEgAaACIAKgA6ADIAOgISAHoAggEAigEAmgEAogEAqgEGKgIaADIAygEA8gECEgD6AQCCAgCKAgIKAJICAKICAKoCALICALoCAMICAMoCACpSEhgSChoCEgAiBBIAIgAiBBICEAEqAhIAMAEaGhIEGgAiABoIEgAaBBABGgAiACoEEgIQAToAIgQSAhIAKhQKEBIEGgAiABoIEgAaBBABGgAYAUIASjASABoECgASACImCiQaHAoaCggqAgoAMgA6ABIAGgwKCCoCCgAyADoAEgAiBAoCEgBaDBIAGgAiBhIECAEQAmIAcgwSABoAIgYSBAgBEAJ6BAoAIgCKAQQKACIAmgEMEgAaACIGEgQIARACqgECCgCyAQC6AQDCAQAShQEKKxIAGgAiACoAMgwKABIAGgAiACoAOgA6AEIAUgBiAGoEEgAaAHoCCgCSAQAiAgoASgBaDAoKCgAiACoAMgBKAHIkCiIKHgoAEggSBgoCCgAaABoQIgYKAgoAGgAqBgoCCgAaABIAigEAkgEGCgASAgoAogEGEgQKABIAsgEAugEAwgEAGsgBEgAaYhIAGgA6AEIAcgIKAIIBAIoBAhIAkgEAmgEAogEAqgEAsgEAugEA2gEGCgASAgoA6gEA8gEA+gEAggIAkgIAqgIAsgIAugIAygIA2gICCgDqAgQKAgoA8gIGCgASABoA+gIAIgwSABoCCgAiACoCCgA6AGIAagByHAoAEgwKABICCgAaACICCgAaCgoAEgIKABoAIgB6AIIBAgoAkgEAmgEUIgISADIEEgAaADoEEgAaAEIASgCiAQCyAQDCAQDKAQDSAQAyADgCSioKBhIECgASABIEGgIQARoCEgAiADoCCgBCCggCEgYBAgMFBgdKAFoCCgBYAVgCWAZiDBIECgASABoCCgAiAGoAegQaAggBEgwKCgoGCgIKABIAEgA=")
    fun request(cursor: String?): ByteArray {
        if (cursor == null) return template.copyOf()
        require(cursor.length <= 16384)
        val root = GoogleNativeProtocol.ProtoFields(template)
        return GoogleNativeProtocol.bytesField(1, root.bytes(1)!! + GoogleNativeProtocol.bytesField(4, cursor.toByteArray())) +
            GoogleNativeProtocol.bytesField(2, root.bytes(2)!!)
    }
    fun decode(bytes: ByteArray): LibraryPage {
        val container = GoogleNativeProtocol.ProtoFields(bytes).message(1)
            ?: throw GoogleProtocolException("Google library response has no container.")
        val entries = container.messages(2)
        require(entries.size <= 5000) { "Google library page exceeds safety limit." }
        val items = entries.map { item ->
            val key = item.string(1)?.takeIf { it.isNotBlank() && it.length <= 4096 }
                ?: throw GoogleProtocolException("Google library item has no identity.")
            val metadata = item.message(2)
            val seconds = metadata?.varint(7) ?: 0L
            LibraryItem(key, seconds.takeIf { it in 0..253402300799L }?.times(1000) ?: 0L,
                item.message(5)?.varint(1) == 1L)
        }
        val cursor = container.string(1)?.takeIf { it.isNotEmpty() }
        require(cursor == null || cursor.length <= 16384)
        return LibraryPage(items.distinctBy { it.id }, cursor)
    }
}

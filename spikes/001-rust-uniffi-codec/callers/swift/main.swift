import Foundation

func bytesField(_ field: UInt64, _ value: [UInt8]) -> [UInt8] {
    var out: [UInt8] = []
    var tag = field << 3 | 2
    while tag & 0xffff_ff80 != 0 {
        out.append(UInt8(tag & 0x7f) | 0x80)
        tag >>= 7
    }
    out.append(UInt8(tag))
    var length = UInt64(value.count)
    while length & 0xffff_ff80 != 0 {
        out.append(UInt8(length & 0x7f) | 0x80)
        length >>= 7
    }
    out.append(UInt8(length))
    return out + value
}

let expectedToken: [UInt8] = [8, 2, 16, 2, 24, 1, 32, 3, 56, 123]
let encoded = Array(try encodeUploadTokenRequest(fileSize: 123))
precondition(encoded == expectedToken, "upload token bytes differ: \(encoded)")

let committed = bytesField(1, bytesField(3, bytesField(1, Array("media-key".utf8))))
precondition(try decodeCommittedMediaKey(response: Data(committed)) == "media-key")

let unrelated = bytesField(4, Array("media-key".utf8))
do {
    _ = try decodeCommittedMediaKey(response: Data(unrelated))
    fatalError("unrelated field must not confirm an upload")
} catch {
    // expected
}

print("SWIFT_SPIKE_OK \(encoded.count) bytes; media key decoded; wrong envelope rejected")

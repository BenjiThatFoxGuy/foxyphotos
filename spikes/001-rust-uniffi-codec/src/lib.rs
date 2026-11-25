//! Throwaway spike: the Google Photos wire codec, ported from Kotlin to Rust.
//! Same byte layout as provider/google-core/GoogleNativeProtocol.kt.

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, uniffi::Record)]
pub struct CommitToken {
    pub number: u64,
    pub opaque: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Error)]
pub enum CodecError {
    InvalidVarint,
    MissingField,
    InvalidUtf8,
}

impl std::fmt::Display for CodecError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CodecError::InvalidVarint => write!(f, "Malformed Google protobuf response."),
            CodecError::MissingField => write!(f, "Upload response is missing a commit token."),
            CodecError::InvalidUtf8 => write!(f, "Response field is not valid UTF-8."),
        }
    }
}

fn push_varint(mut value: u64, out: &mut Vec<u8>) {
    while value & 0xffff_ff80 != 0 {
        out.push(((value & 0x7f) as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

fn varint_field(field: u32, value: u64) -> Vec<u8> {
    let mut out = Vec::new();
    push_varint(((field as u64) << 3), &mut out);
    push_varint(value, &mut out);
    out
}

fn bytes_field(field: u32, value: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    push_varint(((field as u64) << 3) | 2, &mut out);
    push_varint(value.len() as u64, &mut out);
    out.extend_from_slice(value);
    out
}

#[derive(Clone)]
enum Value {
    Varint(u64),
    Bytes(Vec<u8>),
}

struct Fields(std::collections::HashMap<u32, Vec<Value>>);

impl Fields {
    fn parse(data: &[u8]) -> Result<Self, CodecError> {
        let mut fields = std::collections::HashMap::new();
        let mut offset = 0usize;
        while offset < data.len() {
            let (tag, next) = read_varint(data, offset)?;
            offset = next;
            let field = (tag >> 3) as u32;
            let entry = fields.entry(field).or_default();
            match tag & 7 {
                0 => {
                    let (value, next) = read_varint(data, offset)?;
                    offset = next;
                    entry.push(Value::Varint(value));
                }
                2 => {
                    let (length, next) = read_varint(data, offset)?;
                    offset = next;
                    let end = offset.checked_add(length as usize).ok_or(CodecError::InvalidVarint)?;
                    if end > data.len() {
                        return Err(CodecError::InvalidVarint);
                    }
                    entry.push(Value::Bytes(data[offset..end].to_vec()));
                    offset = end;
                }
                _ => return Err(CodecError::InvalidVarint),
            }
        }
        Ok(Self(fields))
    }

    fn varint(&self, field: u32) -> Option<u64> {
        self.0.get(&field).and_then(|values| values.last()).and_then(|v| match v {
            Value::Varint(n) => Some(*n),
            Value::Bytes(_) => None,
        })
    }

    fn bytes(&self, field: u32) -> Option<&Vec<u8>> {
        self.0.get(&field).and_then(|values| values.last()).and_then(|v| match v {
            Value::Varint(_) => None,
            Value::Bytes(b) => Some(b),
        })
    }

    fn message(&self, field: u32) -> Result<Option<Fields>, CodecError> {
        match self.bytes(field) {
            Some(bytes) => Ok(Some(Fields::parse(bytes)?)),
            None => Ok(None),
        }
    }

    fn string(&self, field: u32) -> Result<Option<String>, CodecError> {
        match self.bytes(field) {
            Some(bytes) => Ok(Some(
                std::str::from_utf8(bytes).map_err(|_| CodecError::InvalidUtf8)?.to_string(),
            )),
            None => Ok(None),
        }
    }
}

fn read_varint(data: &[u8], start: usize) -> Result<(u64, usize), CodecError> {
    let mut value = 0u64;
    let mut shift = 0u32;
    let mut offset = start;
    while offset < data.len() && shift < 64 {
        let byte = data[offset];
        offset += 1;
        value |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            return Ok((value, offset));
        }
        shift += 7;
    }
    Err(CodecError::InvalidVarint)
}

#[uniffi::export]
pub fn hash_check_codec(sha1: Vec<u8>) -> Vec<u8> {
    let inner = bytes_field(1, &sha1);
    let request = bytes_field(1, &inner) + bytes_field(2, &[]);
    bytes_field(1, &request)
}

#[uniffi::export]
pub fn encode_upload_token_request(file_size: u64) -> Result<Vec<u8>, CodecError> {
    if file_size == 0 {
        return Err(CodecError::MissingField);
    }
    let mut out = Vec::new();
    out.extend(varint_field(1, 2));
    out.extend(varint_field(2, 2));
    out.extend(varint_field(3, 1));
    out.extend(varint_field(4, 3));
    out.extend(varint_field(7, file_size));
    Ok(out)
}

#[uniffi::export]
pub fn encode_commit_request(
    token: CommitToken,
    file_name: String,
    sha1: Vec<u8>,
    modified_epoch_seconds: u64,
) -> Vec<u8> {
    let mut token_message = varint_field(1, token.number);
    token_message.extend(bytes_field(2, &token.opaque));
    let mut timestamp = varint_field(1, modified_epoch_seconds);
    timestamp.extend(varint_field(2, 46_000_000));
    let mut commit = bytes_field(1, &token_message);
    commit.extend(bytes_field(2, file_name.as_bytes()));
    commit.extend(bytes_field(3, &sha1));
    commit.extend(bytes_field(4, &timestamp));
    commit.extend(varint_field(7, 3));
    commit.extend(varint_field(10, 1));
    let mut device = bytes_field(3, b"Pixel XL");
    device.extend(bytes_field(4, b"Google"));
    device.extend(varint_field(5, 28));
    let mut out = bytes_field(1, &commit);
    out.extend(bytes_field(2, &device));
    out.extend(bytes_field(3, &[1, 3]));
    out
}

#[uniffi::export]
pub fn decode_commit_token(response: Vec<u8>) -> Result<CommitToken, CodecError> {
    let fields = Fields::parse(&response)?;
    Ok(CommitToken {
        number: fields.varint(1).ok_or(CodecError::MissingField)?,
        opaque: fields.bytes(2).ok_or(CodecError::MissingField)?.clone(),
    })
}

#[uniffi::export]
pub fn decode_existing_media_key(response: Vec<u8>) -> Result<Option<String>, CodecError> {
    let fields = Fields::parse(&response)?;
    let key = fields
        .message(1)?
        .and_then(|f| f.message(2).ok().flatten())
        .and_then(|f| f.message(2).ok().flatten())
        .and_then(|f| f.string(1).ok().flatten())
        .filter(|s| !s.is_empty());
    Ok(key)
}

#[uniffi::export]
pub fn decode_committed_media_key(response: Vec<u8>) -> Result<String, CodecError> {
    let fields = Fields::parse(&response)?;
    fields
        .message(1)?
        .and_then(|f| f.message(3).ok().flatten())
        .and_then(|f| f.string(1).ok().flatten())
        .filter(|s| !s.is_empty())
        .ok_or(CodecError::MissingField)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upload_token_matches_kotlin_vector() {
        assert_eq!(
            encode_upload_token_request(123).unwrap(),
            vec![8, 2, 16, 2, 24, 1, 32, 3, 56, 123]
        );
        assert!(encode_upload_token_request(0).is_err());
    }

    #[test]
    fn decodes_committed_key_only_from_expected_envelope() {
        let expected = bytes_field(1, &bytes_field(3, &bytes_field(1, b"media-key")));
        assert_eq!(decode_committed_media_key(expected).unwrap(), "media-key");
        let unrelated = bytes_field(4, b"media-key");
        assert!(decode_committed_media_key(unrelated).is_err());
    }

    #[test]
    fn decodes_existing_key_only_from_hash_check_envelope() {
        assert_eq!(decode_existing_media_key(vec![]).unwrap(), None);
        let existing = bytes_field(1, &bytes_field(2, &bytes_field(2, &bytes_field(1, b"already-there"))));
        assert_eq!(decode_existing_media_key(existing).unwrap(), Some("already-there".into()));
    }

    #[test]
    fn decodes_upload_commit_token() {
        let mut response = varint_field(1, 42);
        response.extend(bytes_field(2, &[1, 2]));
        let token = decode_commit_token(response).unwrap();
        assert_eq!(token.number, 42);
        assert_eq!(token.opaque, vec![1, 2]);
    }
}

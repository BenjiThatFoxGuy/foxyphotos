# Third-party notices

The Google library wire field-mask fixture in `GoogleLibraryProtocol.kt` is reproduced
from `backend/gotohp/api/protobuf.go` in bclone revision
`f6ad6b63ec67694011c686f246f8832cb0e6fb9a`. The Kotlin adapter is independently implemented.
The upstream MIT notice for this fixture follows:

```text
Copyright (C) 2012 by Nick Craig-Wood http://www.craig-wood.com/nick/

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```


The Google Photos adapter will use the `gotohp` backend in [bclone](https://github.com/benjithatfoxguy/bclone) as a behavioural and protocol-provenance reference. It must not copy source wholesale. If any upstream source is copied, this file must be updated with the upstream path, exact commit, licence notice, and a description of the copied material.

Dependencies introduced later must be recorded here when their licences require attribution or notice preservation.

## iCloud protocol and rclone obscure compatibility references

The manual iCloud Photos adapter references bclone/rclone (MIT, Copyright (C) 2012 Nick Craig-Wood) and icloudpd (MIT, Copyright (c) 2016 Nathan Broadbent). The rclone public obscure key is used for config compatibility.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package main

import (
	"encoding/binary"
	"errors"
	"io"
)

// Wire format for every stdout message:
//
//	u32be totalLen || kind(1) || body
//
// KindControl body is raw JSON. KindFrame body is u32be metaLen || metaJSON
// || jpegBytes. One framing scheme keeps stdout unambiguous when binary
// frames share the pipe with control responses.

const (
	KindControl = byte('J')
	KindFrame   = byte('F')
)

const maxMessageSize = 64 << 20 // a full-scale keyframe is ~160 KB today; stay generous

func EncodeMessage(kind byte, body []byte) []byte {
	total := 1 + len(body)
	wire := make([]byte, 4+total)
	binary.BigEndian.PutUint32(wire, uint32(total))
	wire[4] = kind
	copy(wire[5:], body)
	return wire
}

var errTruncated = errors.New("truncated message on stdout stream")

func DecodeMessage(r io.Reader) (kind byte, body []byte, err error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return 0, nil, err
	}
	total := binary.BigEndian.Uint32(lenBuf[:])
	if total < 1 || total > maxMessageSize {
		return 0, nil, errTruncated
	}
	buf := make([]byte, total)
	if _, err := io.ReadFull(r, buf); err != nil {
		return 0, nil, err
	}
	return buf[0], buf[1:], nil
}

func EncodeFrame(meta, jpeg []byte) []byte {
	body := make([]byte, 4+len(meta)+len(jpeg))
	binary.BigEndian.PutUint32(body, uint32(len(meta)))
	copy(body[4:], meta)
	copy(body[4+len(meta):], jpeg)
	return EncodeMessage(KindFrame, body)
}

var errMetaOverrun = errors.New("frame meta length exceeds frame body")

// SplitFrame separates a KindFrame body into its meta JSON and JPEG bytes.
func SplitFrame(body []byte) (meta, jpeg []byte, err error) {
	if len(body) < 4 {
		return nil, nil, errMetaOverrun
	}
	metaLen := binary.BigEndian.Uint32(body)
	if uint64(metaLen)+4 > uint64(len(body)) {
		return nil, nil, errMetaOverrun
	}
	return body[4 : 4+metaLen], body[4+metaLen:], nil
}

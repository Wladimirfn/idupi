package main

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestMessageRoundTrip(t *testing.T) {
	body := []byte(`{"id":7,"ok":true}`)
	wire := EncodeMessage(KindControl, body)

	kind, got, err := DecodeMessage(bytes.NewReader(wire))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if kind != KindControl {
		t.Fatalf("kind: got %q, want %q", kind, KindControl)
	}
	if !bytes.Equal(got, body) {
		t.Fatalf("body mismatch: %q vs %q", got, body)
	}
}

func TestFrameCarriesMetaThenJpegBytes(t *testing.T) {
	meta, _ := json.Marshal(map[string]int{"id": 1, "w": 800, "h": 450})
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3}
	wire := EncodeFrame(meta, jpeg)

	kind, body, err := DecodeMessage(bytes.NewReader(wire))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if kind != KindFrame {
		t.Fatalf("kind: got %q, want %q", kind, KindFrame)
	}

	gotMeta, gotJpeg, err := SplitFrame(body)
	if err != nil {
		t.Fatalf("split: %v", err)
	}
	if !bytes.Equal(gotMeta, meta) {
		t.Fatalf("meta mismatch")
	}
	if !bytes.Equal(gotJpeg, jpeg) {
		t.Fatalf("jpeg mismatch")
	}
}

func TestDecodeRejectsTruncatedWire(t *testing.T) {
	if _, _, err := DecodeMessage(bytes.NewReader([]byte{0, 0})); err == nil {
		t.Fatal("expected error on truncated length prefix")
	}
	full := EncodeMessage(KindControl, []byte(`{}`))
	if _, _, err := DecodeMessage(bytes.NewReader(full[:len(full)-1])); err == nil {
		t.Fatal("expected error on truncated body")
	}
}

func TestSplitFrameRejectsBadMetaLength(t *testing.T) {
	body := []byte{0xFF, 0xFF, 0xFF, 0xFF, 1}
	if _, _, err := SplitFrame(body); err == nil {
		t.Fatal("expected error when meta length exceeds body")
	}
}

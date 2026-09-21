//go:build windows

package main

import (
	"testing"
)

// TestEnumerateMonitorsDoesNotExhaustCallbacks verifies that enumerateMonitors
// can be called thousands of times (simulating streaming and mouse events)
// without panicking due to Go's 2000-callback limit on Windows (syscall.NewCallback).
func TestEnumerateMonitorsDoesNotExhaustCallbacks(t *testing.T) {
	const iterations = 3000
	for i := 0; i < iterations; i++ {
		monitors, err := enumerateMonitors()
		if err != nil {
			t.Fatalf("iteration %d failed: %v", i, err)
		}
		if len(monitors) == 0 {
			t.Fatalf("iteration %d returned 0 monitors", i)
		}
	}
}

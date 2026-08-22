package main

// FitWithin scales a requested frame size down to fit inside the native
// monitor size, preserving the REQUESTED aspect ratio (the server decides
// framing; the helper never crops). Degenerate inputs yield (0, 0).
func FitWithin(nativeW, nativeH, reqW, reqH int32) (int32, int32) {
	if nativeW <= 0 || nativeH <= 0 || reqW <= 0 || reqH <= 0 {
		return 0, 0
	}
	scale := 1.0
	if reqW > nativeW {
		scale = float64(nativeW) / float64(reqW)
	}
	if scaledH := int32(float64(reqH) * scale); scaledH > nativeH {
		// Overflowing height means width must shrink further; recompute
		// from the height constraint to stay within both bounds.
		scale = float64(nativeH) / float64(reqH)
	}
	outW := int32(float64(reqW) * scale)
	outH := int32(float64(reqH) * scale)
	if outW < 1 {
		outW = 1
	}
	if outH < 1 {
		outH = 1
	}
	return outW, outH
}

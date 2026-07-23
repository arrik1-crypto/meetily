// Vendored from k2-fsa/sherpa-onnx (tag v1.13.4), Apache License 2.0.
// https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.4/sherpa-onnx/kotlin-api/
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}

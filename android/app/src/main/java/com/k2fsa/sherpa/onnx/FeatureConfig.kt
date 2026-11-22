// Vendored from k2-fsa/sherpa-onnx (tag v2.0.3), Apache License 2.0.
// https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/sherpa-onnx/kotlin-api/
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}

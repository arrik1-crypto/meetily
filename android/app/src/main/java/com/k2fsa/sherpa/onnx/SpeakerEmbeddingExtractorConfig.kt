// Vendored from k2-fsa/sherpa-onnx (tag v1.10.30), Apache License 2.0.
// https://github.com/k2-fsa/sherpa-onnx/blob/v1.10.30/sherpa-onnx/kotlin-api/
package com.k2fsa.sherpa.onnx

data class SpeakerEmbeddingExtractorConfig(
    val model: String,
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

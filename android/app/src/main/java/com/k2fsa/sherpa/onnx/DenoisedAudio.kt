// Vendored from k2-fsa/sherpa-onnx (tag v2.0.3), Apache License 2.0.
// https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/sherpa-onnx/kotlin-api/
package com.k2fsa.sherpa.onnx

class DenoisedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}

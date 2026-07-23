// Vendored from k2-fsa/sherpa-onnx (tag v1.13.4), Apache License 2.0.
// https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.4/sherpa-onnx/kotlin-api/
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)

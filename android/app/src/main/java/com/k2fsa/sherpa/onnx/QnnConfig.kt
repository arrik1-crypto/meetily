// Vendored from k2-fsa/sherpa-onnx (tag v2.0.3), Apache License 2.0.
// https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.3/sherpa-onnx/kotlin-api/
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
    var htpPerfMode: Boolean = false,
)

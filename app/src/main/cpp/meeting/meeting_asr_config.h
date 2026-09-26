// SPDX-License-Identifier: Apache-2.0
#pragma once

namespace dictai::meeting {

template <typename RecognizerConfig>
inline void use_meeting_rnnt_context(RecognizerConfig& config) noexcept {
    // Model-default (-1) dropped the leading "Bonjour" from French fixture word metadata.
    // Keep the validated historical context until that regression is understood.
    config.streaming.rnnt_right_context = 1;
}

}  // namespace dictai::meeting

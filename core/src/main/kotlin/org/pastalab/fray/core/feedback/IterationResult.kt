package org.pastalab.fray.core.feedback

import java.nio.file.Path

/**
 * End-of-iteration state handed to [FeedbackSource.collect]. Transient only:
 * never serialised, so it is not part of the replay path.
 */
data class IterationResult(
    val iteration: Int,
    val bugFound: Throwable?,
    val reportFolder: Path?,
)

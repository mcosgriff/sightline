package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class BackoffTest {
    @Test
    fun `delays double from 1 s and stop at 30 s`() {
        assertThat((0..8).map { Backoff.delayAfter(it).inWholeSeconds })
            .containsExactly(0L, 1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L).inOrder()
    }

    @Test
    fun `attempts wait out the delay and a success resets it`() {
        val time = TestTimeSource()
        val backoff = Backoff(time)
        assertThat(backoff.canAttempt()).isTrue()
        backoff.failed()
        assertThat(backoff.canAttempt()).isFalse()
        time += 999.milliseconds
        assertThat(backoff.canAttempt()).isFalse()
        time += 1.milliseconds
        assertThat(backoff.canAttempt()).isTrue()
        backoff.failed()
        time += 1.seconds
        assertThat(backoff.canAttempt()).isFalse()
        time += 1.seconds
        assertThat(backoff.canAttempt()).isTrue()
        backoff.succeeded()
        assertThat(backoff.canAttempt()).isTrue()
    }
}

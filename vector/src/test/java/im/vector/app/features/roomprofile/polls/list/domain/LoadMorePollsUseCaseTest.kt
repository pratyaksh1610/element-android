/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.roomprofile.polls.list.domain

import im.vector.app.features.roomprofile.polls.list.data.RoomPollRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.room.poll.LoadedPollsStatus

class LoadMorePollsUseCaseTest {

    private val fakeRoomPollRepository = mockk<RoomPollRepository>()

    private val loadMorePollsUseCase = LoadMorePollsUseCase(
            roomPollRepository = fakeRoomPollRepository,
    )

    @Test
    fun `given repo when execute then correct method of repo is called`() = runTest {
        // Given
        val aRoomId = "roomId"
        val loadedPollsStatus = LoadedPollsStatus(
                canLoadMore = true,
                nbSyncedDays = 10,
                hasCompletedASyncBackward = true,
        )
        coEvery { fakeRoomPollRepository.loadMorePolls(aRoomId) } returns loadedPollsStatus

        // When
        val result = loadMorePollsUseCase.execute(aRoomId)

        // Then
        result shouldBeEqualTo loadedPollsStatus
        coVerify { fakeRoomPollRepository.loadMorePolls(aRoomId) }
    }
}

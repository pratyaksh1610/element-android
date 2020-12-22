/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.peeking

import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomAvatarContent
import org.matrix.android.sdk.api.session.room.model.RoomCanonicalAliasContent
import org.matrix.android.sdk.api.session.room.model.RoomDirectoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomNameContent
import org.matrix.android.sdk.api.session.room.model.RoomTopicContent
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsFilter
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsParams
import org.matrix.android.sdk.api.session.room.peeking.PeekResult
import org.matrix.android.sdk.internal.session.room.alias.GetRoomIdByAliasTask
import org.matrix.android.sdk.internal.session.room.directory.GetPublicRoomTask
import org.matrix.android.sdk.internal.session.room.directory.GetRoomDirectoryVisibilityTask
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface PeekRoomTask : Task<PeekRoomTask.Params, PeekResult> {
    data class Params(
            val roomIdOrAlias: String
    )
}

internal class DefaultPeekRoomTask @Inject constructor(
        private val getRoomIdByAliasTask: GetRoomIdByAliasTask,
        private val getRoomDirectoryVisibilityTask: GetRoomDirectoryVisibilityTask,
        private val getPublicRoomTask: GetPublicRoomTask,
        private val resolveRoomStateTask: ResolveRoomStateTask
) : PeekRoomTask {

    override suspend fun execute(params: PeekRoomTask.Params): PeekResult {
        val roomId: String
        val serverList: List<String>
        val isAlias = MatrixPatterns.isRoomAlias(params.roomIdOrAlias)
        if (isAlias) {
            // get alias description
            val aliasDescription = getRoomIdByAliasTask
                    .execute(GetRoomIdByAliasTask.Params(params.roomIdOrAlias, true))
                    .getOrNull()
                    ?: return PeekResult.UnknownAlias

            roomId = aliasDescription.roomId
            serverList = aliasDescription.servers
        } else {
            roomId = params.roomIdOrAlias
            serverList = emptyList()
        }

        // Is it a public room?
        val publicRepoResult = when (getRoomDirectoryVisibilityTask.execute(GetRoomDirectoryVisibilityTask.Params(roomId))) {
            RoomDirectoryVisibility.PRIVATE -> {
                // We cannot resolve this room :/
                null
            }
            RoomDirectoryVisibility.PUBLIC  -> {
                // Try to find it in directory
                val filter = if (isAlias) PublicRoomsFilter(searchTerm = params.roomIdOrAlias.substring(1))
                else null

                getPublicRoomTask.execute(GetPublicRoomTask.Params(
                        server = serverList.firstOrNull(),
                        publicRoomsParams = PublicRoomsParams(
                                filter = filter,
                                limit = 20.takeIf { filter != null } ?: 100
                        )
                )).chunk?.firstOrNull { it.roomId == roomId }
            }
        }

        if (publicRepoResult != null) {
            return PeekResult.Success(
                    roomId = roomId,
                    alias = publicRepoResult.getPrimaryAlias() ?: params.roomIdOrAlias.takeIf { isAlias },
                    avatarUrl = publicRepoResult.avatarUrl,
                    name = publicRepoResult.name,
                    topic = publicRepoResult.topic,
                    numJoinedMembers = publicRepoResult.numJoinedMembers,
                    viaServers = serverList
            )
        }

        // mm... try to peek state ? maybe the room is not public but yet allow guest to get events?
        // this could be slow
        try {
            val stateEvents = resolveRoomStateTask.execute(ResolveRoomStateTask.Params(roomId))
            val name = stateEvents
                    .lastOrNull { it.type == EventType.STATE_ROOM_NAME && it.stateKey == "" }
                    ?.let { it.content?.toModel<RoomNameContent>()?.name }

            val topic = stateEvents
                    .lastOrNull { it.type == EventType.STATE_ROOM_TOPIC && it.stateKey == "" }
                    ?.let { it.content?.toModel<RoomTopicContent>()?.topic }

            val avatarUrl = stateEvents
                    .lastOrNull { it.type == EventType.STATE_ROOM_AVATAR }
                    ?.let { it.content?.toModel<RoomAvatarContent>()?.avatarUrl }

            val alias = stateEvents
                    .lastOrNull { it.type == EventType.STATE_ROOM_CANONICAL_ALIAS }
                    ?.let { it.content?.toModel<RoomCanonicalAliasContent>()?.canonicalAlias }

            // not sure if it's the right way to do that :/
            val memberCount = stateEvents
                    .filter { it.type == EventType.STATE_ROOM_MEMBER && it.stateKey?.isNotEmpty() == true }
                    .distinctBy { it.stateKey }
                    .count()

            return PeekResult.Success(
                    roomId = roomId,
                    alias = alias,
                    avatarUrl = avatarUrl,
                    name = name,
                    topic = topic,
                    numJoinedMembers = memberCount,
                    viaServers = serverList
            )
        } catch (failure: Throwable) {
            // Would be M_FORBIDDEN if cannot peek :/
            // User XXX not in room !XXX, and room previews are disabled
            return PeekResult.PeekingNotAllowed(
                    roomId = roomId,
                    alias = params.roomIdOrAlias.takeIf { isAlias },
                    viaServers = serverList
            )
        }
    }
}

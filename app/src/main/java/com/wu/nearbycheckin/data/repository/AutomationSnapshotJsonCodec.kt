// @Time: 2026/07/20 18:07
// @Author: MrWu
// @File: AutomationSnapshotJsonCodec.kt
// @Software: Android Studio


package com.wu.nearbycheckin.data.repository


import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.AutomationSession
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInRecord
import com.wu.nearbycheckin.core.model.CheckInTransaction
import com.wu.nearbycheckin.core.model.DualMapCheckInRecord
import com.wu.nearbycheckin.core.model.DualMapState
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapCommandStatus
import com.wu.nearbycheckin.core.model.MapOperationResult
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.NearbyPlaceCandidate


/**
 * 负责 AutomationSnapshot 与可升级 JSON 之间的确定性转换。
 */
internal object AutomationSnapshotJsonCodec {

    // 将完整自动化快照编码为单个 JSON 文本。
    fun encode(snapshot: AutomationSnapshot): String {
        return JSONObject().apply {
            put(SCHEMA_VERSION_KEY, snapshot.schemaVersion)
            put(
                SESSION_KEY,
                snapshot.session?.toJsonObject() ?: JSONObject.NULL
            )
            put(
                CANDIDATES_KEY,
                snapshot.candidates.toCandidateJsonArray()
            )
            put(
                TRANSACTIONS_KEY,
                snapshot.transactions.toTransactionJsonArray()
            )
            put(COMMANDS_KEY, snapshot.commands.toCommandJsonArray())
            put(RECORDS_KEY, snapshot.records.toRecordJsonArray())
            put(
                LEGACY_DUAL_MAP_RECORDS_KEY,
                snapshot.legacyDualMapRecords.toLegacyJsonArray()
            )
            put(
                COMPLETED_PLACE_KEYS_KEY,
                snapshot.completedPlaceKeys.toStringJsonArray()
            )
            put(
                PROVIDER_CANDIDATES_KEY,
                ProviderAutomationJsonCodec.encodeCandidates(
                    snapshot.providerCandidates
                )
            )
            put(
                PROVIDER_TASKS_KEY,
                ProviderAutomationJsonCodec.encodeTasks(
                    snapshot.providerTasks
                )
            )
            put(
                PROVIDER_COMPLETED_KEYS_KEY,
                ProviderAutomationJsonCodec.encodeCompletedKeys(
                    snapshot.providerCompletedKeys
                )
            )
            put(
                PROVIDER_RUNTIME_KEY,
                ProviderAutomationJsonCodec.encodeRuntime(
                    snapshot.providerRuntime
                )
            )
            put(DIAGNOSTICS_KEY, snapshot.diagnostics.toJsonObject())
            put(AUTOMATION_STATUS_KEY, snapshot.automationStatus)
            put(
                AUTO_SPLIT_REQUESTED_AT_MILLIS_KEY,
                snapshot.autoSplitRequestedAtMillis
            )
            put(
                RUNTIME_LOGS_KEY,
                snapshot.runtimeLogs.toRuntimeLogJsonArray()
            )
        }.toString()
    }

    // 将持久化 JSON 安全解析为完整自动化快照。
    fun decode(json: String): AutomationSnapshot? {
        return try {
            val root = JSONObject(json)
            AutomationSnapshot(
                schemaVersion = AutomationSnapshot.CURRENT_SCHEMA_VERSION,
                session = root.optJSONObject(SESSION_KEY)
                    ?.toAutomationSession(),
                candidates = root.optJSONArray(CANDIDATES_KEY)
                    .toCandidateList(),
                transactions = root.optJSONArray(TRANSACTIONS_KEY)
                    .toTransactionList(),
                commands = root.optJSONArray(COMMANDS_KEY)
                    .toCommandList(),
                records = root.optJSONArray(RECORDS_KEY)
                    .toRecordList(),
                legacyDualMapRecords =
                    root.optJSONArray(LEGACY_DUAL_MAP_RECORDS_KEY)
                        .toLegacyDualMapRecordList(),
                completedPlaceKeys =
                    root.optJSONArray(COMPLETED_PLACE_KEYS_KEY)
                        .toStringSet(),
                providerCandidates =
                    ProviderAutomationJsonCodec.decodeCandidates(
                        root.optJSONArray(PROVIDER_CANDIDATES_KEY)
                    ),
                providerTasks = ProviderAutomationJsonCodec.decodeTasks(
                    root.optJSONArray(PROVIDER_TASKS_KEY)
                ),
                providerCompletedKeys =
                    ProviderAutomationJsonCodec.decodeCompletedKeys(
                        root.optJSONArray(PROVIDER_COMPLETED_KEYS_KEY)
                    ),
                providerRuntime = ProviderAutomationJsonCodec.decodeRuntime(
                    root.optJSONObject(PROVIDER_RUNTIME_KEY)
                ),
                diagnostics = root.optJSONObject(DIAGNOSTICS_KEY)
                    ?.toAccessibilityDiagnostics()
                    ?: AccessibilityDiagnostics(),
                automationStatus = root.optString(
                    AUTOMATION_STATUS_KEY
                ),
                autoSplitRequestedAtMillis = root.optLong(
                    AUTO_SPLIT_REQUESTED_AT_MILLIS_KEY
                ),
                runtimeLogs = root.optJSONArray(RUNTIME_LOGS_KEY)
                    .toRuntimeLogList()
            )
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // 将自动化会话编码为 JSON 对象。
    private fun AutomationSession.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(SESSION_ID_KEY, sessionId)
            put(STARTED_AT_MILLIS_KEY, startedAtMillis)
            put(STOPPED_AT_MILLIS_KEY, stoppedAtMillis)
            put(STATUS_KEY, status.name)
        }
    }

    // 将 JSON 对象解析为自动化会话。
    private fun JSONObject.toAutomationSession(): AutomationSession? {
        val status = enumValueOrNull<AutomationSessionStatus>(
            optString(STATUS_KEY)
        ) ?: return null
        return AutomationSession(
            sessionId = optString(SESSION_ID_KEY),
            startedAtMillis = optLong(STARTED_AT_MILLIS_KEY),
            stoppedAtMillis = optLong(STOPPED_AT_MILLIS_KEY),
            status = status
        )
    }

    // 将候选集合编码为 JSON 数组。
    private fun List<NearbyPlaceCandidate>.toCandidateJsonArray():
            JSONArray {
        return JSONArray().also { array ->
            forEach { candidate ->
                array.put(candidate.toJsonObject())
            }
        }
    }

    // 将标准化候选编码为 JSON 对象。
    private fun NearbyPlaceCandidate.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(UNIFIED_PLACE_ID_KEY, unifiedPlaceId)
            put(
                PROVIDER_PLACE_IDS_KEY,
                providerPlaceIds.toStringJsonArray()
            )
            put(NAME_KEY, name)
            put(ADDRESS_KEY, address)
            put(CATEGORY_KEY, category)
            put(LATITUDE_KEY, latitude)
            put(LONGITUDE_KEY, longitude)
            put(DISTANCE_METERS_KEY, distanceMeters)
            put(LOCATION_ACCURACY_METERS_KEY, locationAccuracyMeters.toDouble())
            put(SPEED_KMH_KEY, speedKmh.toDouble())
            put(LOCATION_TIME_MILLIS_KEY, locationTimeMillis)
            put(FIRST_SEEN_AT_MILLIS_KEY, firstSeenAtMillis)
            put(OBSERVED_AT_MILLIS_KEY, observedAtMillis)
            put(DEVICE_LATITUDE_KEY, deviceLatitude.takeIf { it.isFinite() } ?: 0.0)
            put(DEVICE_LONGITUDE_KEY, deviceLongitude.takeIf { it.isFinite() } ?: 0.0)
        }
    }

    // 将 JSON 数组解析为候选集合。
    private fun JSONArray?.toCandidateList(): List<NearbyPlaceCandidate> {
        return mapObjects { item ->
            item.toNearbyPlaceCandidate()
        }
    }

    // 将 JSON 对象解析为标准化候选。
    private fun JSONObject.toNearbyPlaceCandidate():
            NearbyPlaceCandidate? {
        val unifiedPlaceId = optString(UNIFIED_PLACE_ID_KEY)
        if (unifiedPlaceId.isBlank()) {
            return null
        }
        return NearbyPlaceCandidate(
            unifiedPlaceId = unifiedPlaceId,
            providerPlaceIds = optJSONArray(PROVIDER_PLACE_IDS_KEY)
                .toStringSet(),
            name = optString(NAME_KEY),
            address = optString(ADDRESS_KEY),
            category = optString(CATEGORY_KEY),
            latitude = optDouble(LATITUDE_KEY),
            longitude = optDouble(LONGITUDE_KEY),
            distanceMeters = optInt(DISTANCE_METERS_KEY),
            locationAccuracyMeters =
                optDouble(LOCATION_ACCURACY_METERS_KEY).toFloat(),
            speedKmh = optDouble(SPEED_KMH_KEY).toFloat(),
            locationTimeMillis = optLong(LOCATION_TIME_MILLIS_KEY),
            firstSeenAtMillis = optLong(FIRST_SEEN_AT_MILLIS_KEY),
            observedAtMillis = optLong(OBSERVED_AT_MILLIS_KEY),
            deviceLatitude = optDouble(DEVICE_LATITUDE_KEY, 0.0)
                .takeIf { it.isFinite() } ?: 0.0,
            deviceLongitude = optDouble(DEVICE_LONGITUDE_KEY, 0.0)
                .takeIf { it.isFinite() } ?: 0.0
        )
    }

    // 将事务集合编码为 JSON 数组。
    private fun List<CheckInTransaction>.toTransactionJsonArray():
            JSONArray {
        return JSONArray().also { array ->
            forEach { transaction ->
                array.put(transaction.toJsonObject())
            }
        }
    }

    // 将双地图事务编码为 JSON 对象。
    private fun CheckInTransaction.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(TRANSACTION_ID_KEY, transactionId)
            put(SESSION_ID_KEY, sessionId)
            put(CANDIDATE_KEY, candidate.toJsonObject())
            put(STATE_KEY, state.toJsonObject())
            put(CREATED_AT_MILLIS_KEY, createdAtMillis)
            put(UPDATED_AT_MILLIS_KEY, updatedAtMillis)
        }
    }

    // 将 JSON 数组解析为事务集合。
    private fun JSONArray?.toTransactionList():
            List<CheckInTransaction> {
        return mapObjects { item ->
            item.toCheckInTransaction()
        }
    }

    // 将 JSON 对象解析为双地图事务。
    private fun JSONObject.toCheckInTransaction():
            CheckInTransaction? {
        val candidate = optJSONObject(CANDIDATE_KEY)
            ?.toNearbyPlaceCandidate() ?: return null
        val state = optJSONObject(STATE_KEY)
            ?.toDualMapState() ?: return null
        val transactionId = optString(TRANSACTION_ID_KEY)
        if (transactionId.isBlank()) {
            return null
        }
        return CheckInTransaction(
            transactionId = transactionId,
            sessionId = optString(SESSION_ID_KEY),
            candidate = candidate,
            state = state,
            createdAtMillis = optLong(CREATED_AT_MILLIS_KEY),
            updatedAtMillis = optLong(UPDATED_AT_MILLIS_KEY)
        )
    }

    // 将纯状态机状态编码为 JSON 对象。
    private fun DualMapState.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(STATUS_KEY, status.name)
            put(
                BAIDU_RESULT_KEY,
                baiduResult?.toJsonObject() ?: JSONObject.NULL
            )
            put(
                AMAP_RESULT_KEY,
                amapResult?.toJsonObject() ?: JSONObject.NULL
            )
            put(BAIDU_ATTEMPTS_KEY, baiduAttempts)
            put(AMAP_ATTEMPTS_KEY, amapAttempts)
            put(BAIDU_TERMINAL_KEY, baiduTerminal)
            put(AMAP_TERMINAL_KEY, amapTerminal)
            put(RETRY_PROVIDER_KEY, retryProvider?.name.orEmpty())
            put(RETRY_AT_MILLIS_KEY, retryAtMillis)
            put(BLOCKED_REASON_KEY, blockedReason)
        }
    }

    // 将 JSON 对象解析为纯状态机状态。
    private fun JSONObject.toDualMapState(): DualMapState? {
        val status = enumValueOrNull<DualMapTransactionStatus>(
            optString(STATUS_KEY)
        ) ?: return null
        val baiduResult = optJSONObject(BAIDU_RESULT_KEY)
            ?.toMapOperationResult()
        val amapResult = optJSONObject(AMAP_RESULT_KEY)
            ?.toMapOperationResult()
        return DualMapState(
            status = status,
            baiduResult = baiduResult,
            amapResult = amapResult,
            baiduAttempts = optInt(BAIDU_ATTEMPTS_KEY),
            amapAttempts = optInt(AMAP_ATTEMPTS_KEY),
            baiduTerminal = if (has(BAIDU_TERMINAL_KEY)) {
                optBoolean(BAIDU_TERMINAL_KEY)
            } else {
                baiduResult?.status in SUCCESSFUL_OPERATION_STATUSES
            },
            amapTerminal = if (has(AMAP_TERMINAL_KEY)) {
                optBoolean(AMAP_TERMINAL_KEY)
            } else {
                amapResult?.status in SUCCESSFUL_OPERATION_STATUSES
            },
            retryProvider = enumValueOrNull<MapProvider>(
                optString(RETRY_PROVIDER_KEY)
            ),
            retryAtMillis = optLong(RETRY_AT_MILLIS_KEY),
            blockedReason = optString(BLOCKED_REASON_KEY)
        )
    }

    // 将命令集合编码为 JSON 数组。
    private fun List<MapCommand>.toCommandJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { command ->
                array.put(command.toJsonObject())
            }
        }
    }

    // 将持久化命令编码为 JSON 对象。
    private fun MapCommand.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(COMMAND_ID_KEY, commandId)
            put(TRANSACTION_ID_KEY, transactionId)
            put(PROVIDER_KEY, provider.name)
            put(ATTEMPT_KEY, attempt)
            put(CREATED_AT_MILLIS_KEY, createdAtMillis)
            put(AVAILABLE_AT_MILLIS_KEY, availableAtMillis)
            put(STATUS_KEY, status.name)
            put(WORKER_ID_KEY, workerId)
            put(LEASE_UNTIL_MILLIS_KEY, leaseUntilMillis)
            put(COMPLETED_AT_MILLIS_KEY, completedAtMillis)
            put(RESULT_KEY, result?.toJsonObject() ?: JSONObject.NULL)
        }
    }

    // 将 JSON 数组解析为持久化命令集合。
    private fun JSONArray?.toCommandList(): List<MapCommand> {
        return mapObjects { item ->
            item.toMapCommand()
        }
    }

    // 将 JSON 对象解析为持久化命令。
    private fun JSONObject.toMapCommand(): MapCommand? {
        val commandId = optString(COMMAND_ID_KEY)
        val transactionId = optString(TRANSACTION_ID_KEY)
        val provider = enumValueOrNull<MapProvider>(
            optString(PROVIDER_KEY)
        ) ?: return null
        val status = enumValueOrNull<MapCommandStatus>(
            optString(STATUS_KEY)
        ) ?: return null
        if (commandId.isBlank() || transactionId.isBlank()) {
            return null
        }
        return MapCommand(
            commandId = commandId,
            transactionId = transactionId,
            provider = provider,
            attempt = optInt(ATTEMPT_KEY),
            createdAtMillis = optLong(CREATED_AT_MILLIS_KEY),
            availableAtMillis = optLong(AVAILABLE_AT_MILLIS_KEY),
            status = status,
            workerId = optString(WORKER_ID_KEY),
            leaseUntilMillis = optLong(LEASE_UNTIL_MILLIS_KEY),
            completedAtMillis = optLong(COMPLETED_AT_MILLIS_KEY),
            result = optJSONObject(RESULT_KEY)
                ?.toMapOperationResult()
        )
    }

    // 将地图端操作结果编码为 JSON 对象。
    private fun MapOperationResult.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(PROVIDER_KEY, provider.name)
            put(FLOW_VERSION_KEY, flowVersion)
            put(STATUS_KEY, status.name)
            put(STARTED_AT_KEY, startedAt)
            put(COMPLETED_AT_KEY, completedAt)
            put(ATTEMPTS_KEY, attempts)
            put(MATCHED_PLACE_TEXT_KEY, matchedPlaceText)
            put(MATCHED_NODE_KEY, matchedNode)
            put(FAILURE_CODE_KEY, failureCode)
            put(FAILURE_MESSAGE_KEY, failureMessage)
            put(EVIDENCE_TEXT_KEY, evidenceText)
            put(COMMAND_ID_KEY, commandId)
            put(TRANSACTION_ID_KEY, transactionId)
        }
    }

    // 将 JSON 对象解析为地图端操作结果。
    private fun JSONObject.toMapOperationResult():
            MapOperationResult? {
        val provider = enumValueOrNull<MapProvider>(
            optString(PROVIDER_KEY)
        ) ?: return null
        val status = enumValueOrNull<MapOperationStatus>(
            optString(STATUS_KEY)
        ) ?: return null
        return MapOperationResult(
            provider = provider,
            flowVersion = optString(FLOW_VERSION_KEY),
            status = status,
            startedAt = optLong(STARTED_AT_KEY),
            completedAt = optLong(COMPLETED_AT_KEY),
            attempts = optInt(ATTEMPTS_KEY),
            matchedPlaceText = optString(MATCHED_PLACE_TEXT_KEY),
            matchedNode = optString(MATCHED_NODE_KEY),
            failureCode = optString(FAILURE_CODE_KEY),
            failureMessage = optString(FAILURE_MESSAGE_KEY),
            evidenceText = optString(EVIDENCE_TEXT_KEY),
            commandId = optString(COMMAND_ID_KEY),
            transactionId = optString(TRANSACTION_ID_KEY)
        )
    }

    // 将本地记录集合编码为 JSON 数组。
    private fun List<CheckInRecord>.toRecordJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { record ->
                array.put(record.toJsonObject())
            }
        }
    }

    // 将本地记录编码为 JSON 对象。
    private fun CheckInRecord.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(ID_KEY, id)
            put(POI_UID_KEY, poiUid)
            put(POI_NAME_KEY, poiName)
            put(ADDRESS_KEY, address)
            put(CATEGORY_KEY, category)
            put(LATITUDE_KEY, latitude)
            put(LONGITUDE_KEY, longitude)
            put(DISTANCE_METERS_KEY, distanceMeters)
            put(STAY_DURATION_MILLIS_KEY, stayDurationMillis)
            put(CHECK_IN_TIME_MILLIS_KEY, checkInTimeMillis)
            put(PROVIDER_KEY, provider?.name ?: "")
            put(EVIDENCE_SUMMARY_KEY, evidenceSummary)
        }
    }

    // 将 JSON 数组解析为本地记录集合。
    private fun JSONArray?.toRecordList(): List<CheckInRecord> {
        return mapObjects { item ->
            item.toCheckInRecord()
        }
    }

    // 将 JSON 对象解析为本地记录。
    private fun JSONObject.toCheckInRecord(): CheckInRecord? {
        val id = optString(ID_KEY)
        if (id.isBlank()) {
            return null
        }
        return CheckInRecord(
            id = id,
            poiUid = optString(POI_UID_KEY),
            poiName = optString(POI_NAME_KEY),
            address = optString(ADDRESS_KEY),
            category = optString(CATEGORY_KEY),
            latitude = optDouble(LATITUDE_KEY),
            longitude = optDouble(LONGITUDE_KEY),
            distanceMeters = optInt(DISTANCE_METERS_KEY),
            stayDurationMillis = optLong(STAY_DURATION_MILLIS_KEY),
            checkInTimeMillis = optLong(CHECK_IN_TIME_MILLIS_KEY),
            provider = optString(PROVIDER_KEY).takeIf(String::isNotBlank)
                ?.let { value ->
                    runCatching { MapProvider.valueOf(value) }.getOrNull()
                },
            evidenceSummary = optString(EVIDENCE_SUMMARY_KEY)
        )
    }

    // 将旧双地图记录集合编码为 JSON 数组。
    private fun List<DualMapCheckInRecord>.toLegacyJsonArray():
            JSONArray {
        return JSONArray().also { array ->
            forEach { record ->
                array.put(record.toJsonObject())
            }
        }
    }

    // 将旧双地图记录编码为 JSON 对象。
    private fun DualMapCheckInRecord.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(RECORD_ID_KEY, recordId)
            put(SESSION_ID_KEY, sessionId)
            put(UNIFIED_PLACE_ID_KEY, unifiedPlaceId)
            put(PLACE_NAME_KEY, placeName)
            put(PLACE_ADDRESS_KEY, placeAddress)
            put(LOCATION_ACCURACY_KEY, locationAccuracy.toDouble())
            put(SPEED_KMH_KEY, speedKmh.toDouble())
            put(DISTANCE_METERS_KEY, distanceMeters)
            put(BAIDU_RESULT_KEY, baiduResult.toJsonObject())
            put(AMAP_RESULT_KEY, amapResult.toJsonObject())
            put(OVERALL_STATUS_KEY, overallStatus)
            put(CREATED_AT_KEY, createdAt)
            put(COMPLETED_AT_KEY, completedAt)
        }
    }

    // 将 JSON 数组解析为旧双地图记录集合。
    private fun JSONArray?.toLegacyDualMapRecordList():
            List<DualMapCheckInRecord> {
        return mapObjects { item ->
            item.toDualMapCheckInRecord()
        }
    }

    // 将 JSON 对象解析为旧双地图记录。
    private fun JSONObject.toDualMapCheckInRecord():
            DualMapCheckInRecord? {
        val recordId = optString(RECORD_ID_KEY)
        val baiduResult = optJSONObject(BAIDU_RESULT_KEY)
            ?.toMapOperationResult() ?: return null
        val amapResult = optJSONObject(AMAP_RESULT_KEY)
            ?.toMapOperationResult() ?: return null
        if (recordId.isBlank()) {
            return null
        }
        return DualMapCheckInRecord(
            recordId = recordId,
            sessionId = optString(SESSION_ID_KEY),
            unifiedPlaceId = optString(UNIFIED_PLACE_ID_KEY),
            placeName = optString(PLACE_NAME_KEY),
            placeAddress = optString(PLACE_ADDRESS_KEY),
            locationAccuracy = optDouble(LOCATION_ACCURACY_KEY).toFloat(),
            speedKmh = optDouble(SPEED_KMH_KEY).toFloat(),
            distanceMeters = optInt(DISTANCE_METERS_KEY),
            baiduResult = baiduResult,
            amapResult = amapResult,
            overallStatus = optString(OVERALL_STATUS_KEY),
            createdAt = optLong(CREATED_AT_KEY),
            completedAt = optLong(COMPLETED_AT_KEY)
        )
    }

    // 将诊断状态编码为 JSON 对象。
    private fun AccessibilityDiagnostics.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(BAIDU_READY_KEY, baiduReady)
            put(AMAP_READY_KEY, amapReady)
            put(TOTAL_WINDOW_COUNT_KEY, totalWindowCount)
            put(TARGET_WINDOW_COUNT_KEY, targetWindowCount)
            put(TARGET_NODE_COUNT_KEY, targetNodeCount)
            put(BAIDU_CLICKABLE_NODE_COUNT_KEY, baiduClickableNodeCount)
            put(BAIDU_CANDIDATE_SUMMARY_KEY, baiduCandidateSummary)
            put(AMAP_CLICKABLE_NODE_COUNT_KEY, amapClickableNodeCount)
            put(AMAP_CANDIDATE_SUMMARY_KEY, amapCandidateSummary)
            put(REASON_KEY, reason)
            put(LAST_UPDATED_MILLIS_KEY, lastUpdatedMillis)
        }
    }

    // 将 JSON 对象解析为诊断状态。
    private fun JSONObject.toAccessibilityDiagnostics():
            AccessibilityDiagnostics {
        return AccessibilityDiagnostics(
            baiduReady = optBoolean(BAIDU_READY_KEY),
            amapReady = optBoolean(AMAP_READY_KEY),
            totalWindowCount = optInt(TOTAL_WINDOW_COUNT_KEY),
            targetWindowCount = optInt(TARGET_WINDOW_COUNT_KEY),
            targetNodeCount = optInt(TARGET_NODE_COUNT_KEY),
            baiduClickableNodeCount =
                optInt(BAIDU_CLICKABLE_NODE_COUNT_KEY),
            baiduCandidateSummary =
                optString(BAIDU_CANDIDATE_SUMMARY_KEY),
            amapClickableNodeCount =
                optInt(AMAP_CLICKABLE_NODE_COUNT_KEY),
            amapCandidateSummary =
                optString(AMAP_CANDIDATE_SUMMARY_KEY),
            reason = optString(REASON_KEY),
            lastUpdatedMillis = optLong(LAST_UPDATED_MILLIS_KEY)
        )
    }

    // 将运行日志集合编码为 JSON 数组。
    private fun List<AutomationLogEntry>.toRuntimeLogJsonArray():
            JSONArray {
        return JSONArray().also { array ->
            forEach { entry ->
                array.put(entry.toJsonObject())
            }
        }
    }

    // 将单条结构化运行日志编码为 JSON 对象。
    private fun AutomationLogEntry.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(LOG_TIMESTAMP_MILLIS_KEY, timestampMillis)
            put(LOG_LEVEL_KEY, level.name)
            put(LOG_SOURCE_KEY, source)
            put(LOG_MESSAGE_KEY, message)
        }
    }

    // 将 JSON 数组解析为结构化运行日志集合。
    private fun JSONArray?.toRuntimeLogList():
            List<AutomationLogEntry> {
        return mapObjects { item ->
            item.toRuntimeLogEntry()
        }.takeLast(MAX_RUNTIME_LOG_COUNT)
    }

    // 将 JSON 对象解析为单条结构化运行日志。
    private fun JSONObject.toRuntimeLogEntry(): AutomationLogEntry? {
        val level = enumValueOrNull<AutomationLogLevel>(
            optString(LOG_LEVEL_KEY)
        ) ?: return null
        val message = optString(LOG_MESSAGE_KEY)
        if (message.isBlank()) {
            return null
        }
        return AutomationLogEntry(
            timestampMillis = optLong(LOG_TIMESTAMP_MILLIS_KEY),
            level = level,
            source = optString(LOG_SOURCE_KEY),
            message = message
        )
    }

    // 将字符串集合编码为 JSON 数组。
    private fun Set<String>.toStringJsonArray(): JSONArray {
        return JSONArray().also { array ->
            sorted().forEach { value ->
                array.put(value)
            }
        }
    }

    // 将 JSON 数组解析为去重字符串集合。
    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) {
            return emptySet()
        }
        return buildSet {
            for (index in 0 until length()) {
                optString(index)
                    .takeIf { value -> value.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    // 将 JSON 对象数组映射为非空领域对象列表。
    private inline fun <T> JSONArray?.mapObjects(
        transform: (JSONObject) -> T?
    ): List<T> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                transform(item)?.let(::add)
            }
        }
    }

    // 将枚举名称安全转换为指定枚举值。
    private inline fun <reified T : Enum<T>> enumValueOrNull(
        value: String
    ): T? {
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val SCHEMA_VERSION_KEY = "schemaVersion"
    private const val SESSION_KEY = "session"
    private const val CANDIDATES_KEY = "candidates"
    private const val TRANSACTIONS_KEY = "transactions"
    private const val COMMANDS_KEY = "commands"
    private const val RECORDS_KEY = "records"
    private const val LEGACY_DUAL_MAP_RECORDS_KEY = "legacyDualMapRecords"
    private const val COMPLETED_PLACE_KEYS_KEY = "completedPlaceKeys"
    private const val PROVIDER_CANDIDATES_KEY = "providerCandidates"
    private const val PROVIDER_TASKS_KEY = "providerTasks"
    private const val PROVIDER_COMPLETED_KEYS_KEY =
        "providerCompletedKeys"
    private const val PROVIDER_RUNTIME_KEY = "providerRuntime"
    private const val DIAGNOSTICS_KEY = "diagnostics"
    private const val AUTOMATION_STATUS_KEY = "automationStatus"
    private const val AUTO_SPLIT_REQUESTED_AT_MILLIS_KEY =
        "autoSplitRequestedAtMillis"
    private const val RUNTIME_LOGS_KEY = "runtimeLogs"
    private const val LOG_TIMESTAMP_MILLIS_KEY = "timestampMillis"
    private const val LOG_LEVEL_KEY = "level"
    private const val LOG_SOURCE_KEY = "source"
    private const val LOG_MESSAGE_KEY = "message"
    private const val SESSION_ID_KEY = "sessionId"
    private const val STARTED_AT_MILLIS_KEY = "startedAtMillis"
    private const val STOPPED_AT_MILLIS_KEY = "stoppedAtMillis"
    private const val STATUS_KEY = "status"
    private const val UNIFIED_PLACE_ID_KEY = "unifiedPlaceId"
    private const val PROVIDER_PLACE_IDS_KEY = "providerPlaceIds"
    private const val NAME_KEY = "name"
    private const val ADDRESS_KEY = "address"
    private const val CATEGORY_KEY = "category"
    private const val LATITUDE_KEY = "latitude"
    private const val LONGITUDE_KEY = "longitude"
    private const val DEVICE_LATITUDE_KEY = "deviceLatitude"
    private const val DEVICE_LONGITUDE_KEY = "deviceLongitude"
    private const val DISTANCE_METERS_KEY = "distanceMeters"
    private const val LOCATION_ACCURACY_METERS_KEY =
        "locationAccuracyMeters"
    private const val SPEED_KMH_KEY = "speedKmh"
    private const val LOCATION_TIME_MILLIS_KEY = "locationTimeMillis"
    private const val FIRST_SEEN_AT_MILLIS_KEY = "firstSeenAtMillis"
    private const val OBSERVED_AT_MILLIS_KEY = "observedAtMillis"
    private const val TRANSACTION_ID_KEY = "transactionId"
    private const val CANDIDATE_KEY = "candidate"
    private const val STATE_KEY = "state"
    private const val CREATED_AT_MILLIS_KEY = "createdAtMillis"
    private const val UPDATED_AT_MILLIS_KEY = "updatedAtMillis"
    private const val BAIDU_RESULT_KEY = "baiduResult"
    private const val AMAP_RESULT_KEY = "amapResult"
    private const val BAIDU_ATTEMPTS_KEY = "baiduAttempts"
    private const val AMAP_ATTEMPTS_KEY = "amapAttempts"
    private const val BAIDU_TERMINAL_KEY = "baiduTerminal"
    private const val AMAP_TERMINAL_KEY = "amapTerminal"
    private const val RETRY_PROVIDER_KEY = "retryProvider"
    private const val RETRY_AT_MILLIS_KEY = "retryAtMillis"
    private const val BLOCKED_REASON_KEY = "blockedReason"
    private const val COMMAND_ID_KEY = "commandId"
    private const val PROVIDER_KEY = "provider"
    private const val ATTEMPT_KEY = "attempt"
    private const val AVAILABLE_AT_MILLIS_KEY = "availableAtMillis"
    private const val WORKER_ID_KEY = "workerId"
    private const val LEASE_UNTIL_MILLIS_KEY = "leaseUntilMillis"
    private const val COMPLETED_AT_MILLIS_KEY = "completedAtMillis"
    private const val RESULT_KEY = "result"
    private const val FLOW_VERSION_KEY = "flowVersion"
    private const val STARTED_AT_KEY = "startedAt"
    private const val COMPLETED_AT_KEY = "completedAt"
    private const val ATTEMPTS_KEY = "attempts"
    private const val MATCHED_PLACE_TEXT_KEY = "matchedPlaceText"
    private const val MATCHED_NODE_KEY = "matchedNode"
    private const val FAILURE_CODE_KEY = "failureCode"
    private const val FAILURE_MESSAGE_KEY = "failureMessage"
    private const val EVIDENCE_TEXT_KEY = "evidenceText"
    private const val ID_KEY = "id"
    private const val POI_UID_KEY = "poiUid"
    private const val POI_NAME_KEY = "poiName"
    private const val STAY_DURATION_MILLIS_KEY = "stayDurationMillis"
    private const val CHECK_IN_TIME_MILLIS_KEY = "checkInTimeMillis"
    private const val EVIDENCE_SUMMARY_KEY = "evidenceSummary"
    private const val RECORD_ID_KEY = "recordId"
    private const val PLACE_NAME_KEY = "placeName"
    private const val PLACE_ADDRESS_KEY = "placeAddress"
    private const val LOCATION_ACCURACY_KEY = "locationAccuracy"
    private const val OVERALL_STATUS_KEY = "overallStatus"
    private const val CREATED_AT_KEY = "createdAt"
    private const val BAIDU_READY_KEY = "baiduReady"
    private const val AMAP_READY_KEY = "amapReady"
    private const val TOTAL_WINDOW_COUNT_KEY = "totalWindowCount"
    private const val TARGET_WINDOW_COUNT_KEY = "targetWindowCount"
    private const val TARGET_NODE_COUNT_KEY = "targetNodeCount"
    private const val BAIDU_CLICKABLE_NODE_COUNT_KEY =
        "baiduClickableNodeCount"
    private const val BAIDU_CANDIDATE_SUMMARY_KEY =
        "baiduCandidateSummary"
    private const val AMAP_CLICKABLE_NODE_COUNT_KEY =
        "amapClickableNodeCount"
    private const val AMAP_CANDIDATE_SUMMARY_KEY =
        "amapCandidateSummary"
    private const val REASON_KEY = "reason"
    private const val LAST_UPDATED_MILLIS_KEY = "lastUpdatedMillis"
    private const val MAX_RUNTIME_LOG_COUNT = 300
    private val SUCCESSFUL_OPERATION_STATUSES = setOf(
        MapOperationStatus.SUCCESS,
        MapOperationStatus.ALREADY_COMPLETED
    )
}

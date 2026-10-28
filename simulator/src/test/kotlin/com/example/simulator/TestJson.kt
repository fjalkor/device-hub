package com.example.simulator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

fun String.toJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

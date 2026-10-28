package com.example.devicehub.device

class DeviceNotFoundException(name: String) : RuntimeException("device '$name' not found")

class DeviceOfflineException(name: String) : RuntimeException("device '$name' is offline")

/** The device broke the protocol; its connection should be closed. */
class ProtocolViolationException(message: String) : RuntimeException(message)
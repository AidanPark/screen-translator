package com.galaxy.airviewdictionary.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class AVDRepository {

    protected val TAG = javaClass.simpleName

    private var avdCoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    private val lock = Any()
    private var referenceCount = 0

    fun acquire() {
        synchronized(lock) {
            val wasZero = referenceCount == 0
            if (wasZero) {
                avdCoroutineScope = CoroutineScope(Dispatchers.IO + Job()) // 스코프 재생성
            }
            referenceCount++
            if (wasZero) {
                // 참조가 0 -> 1 로 되살아날 때의 훅.
                // onZeroReferences 에서 해제한 자원을 하위 클래스가 재초기화할 수 있게 한다.
                onFirstReference()
            }
        }
    }

    protected fun hasActiveReferences(): Boolean {
        synchronized(lock) {
            return referenceCount > 0
        }
    }

    fun release() {
        synchronized(lock) {
            if (referenceCount > 0) {
                referenceCount--
                if (referenceCount == 0) {
                    avdCoroutineScope.launch {
                        onZeroReferences()
                        avdCoroutineScope.cancel()
                    }
                }
            }
        }
    }

    protected fun launchInAVDCoroutineScope(block: suspend CoroutineScope.() -> Unit): Job {
        return avdCoroutineScope.launch(block = block)
    }

    // 자원 해제 등 비동기 작업 수행
    protected open fun onZeroReferences() {

    }

    // 참조가 0 -> 1 로 되살아날 때(재사용 시작) 동기 호출. 자원 재초기화 용.
    protected open fun onFirstReference() {

    }

}


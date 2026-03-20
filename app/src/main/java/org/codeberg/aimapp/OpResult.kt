package org.codeberg.aimapp

sealed class OpResult {
    data class Success(val output: String) : OpResult()
    data class Failure(val exception: Exception) : OpResult()

    val isSuccess: Boolean get() = this is Success

    fun exceptionOrNull(): Exception? = (this as? Failure)?.exception

    inline fun onSuccess(block: (String) -> Unit): OpResult {
        if (this is Success) block(output)
        return this
    }

    inline fun onFailure(block: (Exception) -> Unit): OpResult {
        if (this is Failure) block(exception)
        return this
    }

    companion object {
        fun success(output: String) = Success(output)
        fun failure(exception: Exception) = Failure(exception)
    }
}

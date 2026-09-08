package com.videoai.worker.processor;

/** 无法证明执行已经可靠收敛，必须交给容器重投，不确认 offset。 */
public class UnsettledTaskException extends RuntimeException {
    public UnsettledTaskException(String message) { super(message); }
}

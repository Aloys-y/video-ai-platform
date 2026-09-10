package com.videoai.common.analysis;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
/** 同代次执行权；显式传递到子线程，不使用可泄漏的InheritableThreadLocal。 */
public final class ExecutionOwnership implements AutoCloseable {
 public static final class Token {
  public final String taskId,owner; public final int executionNo;
  private final AtomicBoolean invalid=new AtomicBoolean(); private volatile long validUntil;
  private java.util.function.BooleanSupplier externalValidity=()->true;
  private java.util.function.Supplier<AutoCloseable> children=()->()->{};
  public Token(String taskId,int executionNo,String owner,long validUntil) {this.taskId=taskId;this.executionNo=executionNo;this.owner=owner;this.validUntil=validUntil;}
  public void renewed(long until) {validUntil=until;}
  public void invalidate() {invalid.set(true);}
  public Token(String taskId,int executionNo,String owner,java.util.function.BooleanSupplier valid,java.util.function.Supplier<AutoCloseable> children) {
   this(taskId,executionNo,owner,Long.MAX_VALUE);this.externalValidity=valid;this.children=children;
  }
  public AutoCloseable retainChild(){return children.get();}
  public boolean valid() {return !invalid.get() && System.nanoTime()<validUntil && externalValidity.getAsBoolean();}
  public void check() throws InterruptedIOException {if(!valid())throw new InterruptedIOException("执行所有权已失效");}
 }
 private static final ThreadLocal<Token> CURRENT=new ThreadLocal<>();private final Token previous;
 private ExecutionOwnership(Token token) {previous=CURRENT.get();if(token==null)CURRENT.remove();else CURRENT.set(token);}
 public static Token current() {return CURRENT.get();}
 public static ExecutionOwnership bind(Token token) {return new ExecutionOwnership(token);}
 public static void check() throws InterruptedIOException {if(CURRENT.get()!=null)CURRENT.get().check();}
 public void close() {if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}

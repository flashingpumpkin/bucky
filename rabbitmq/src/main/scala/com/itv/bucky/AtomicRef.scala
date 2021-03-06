package com.itv.bucky

object AtomicRef {

  import java.util.concurrent.atomic.AtomicReference
  import annotation.tailrec

  type Ref[A] = AtomicReference[A]

  implicit class Atomic[A](val atomic: AtomicReference[A]) {
    @tailrec final def update(f: A => A): A = {
      val oldValue = atomic.get()
      val newValue = f(oldValue)
      if (atomic.compareAndSet(oldValue, newValue)) newValue else update(f)
    }

    @tailrec final def modify[R](f: A => (R, A)): (R, A) = {
      val oldValue = atomic.get()
      val newValue = f(oldValue)
      if (atomic.compareAndSet(oldValue, newValue._2)) newValue else modify(f)
    }

    def get: A = atomic.get
  }

  object Ref {
    def apply[A](a: A) = new Ref[A](a)
  }
}

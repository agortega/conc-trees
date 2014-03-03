package org.scalablitz



import scala.reflect.ClassTag



class ConqueueBuffer[@specialized(Byte, Char, Int, Long, Float, Double) T: ClassTag](val k: Int, val isLazy: Boolean, private var conqueue: Conqueue[T]) {
  import Conc._
  import Conqueue._

  require(k > 0)

  private var leftChunk = new Array[T](k)
  private var leftIndex = k - 1
  private var leftStart = k - 1
  private var rightChunk = new Array[T](k)
  private var rightIndex = 0
  private var rightStart = 0

  def this(k: Int) = this(k, true, Conqueue.Lazy(Nil, Conqueue.empty, Nil))

  def this(k: Int, lazyConqueue: Boolean) = this(k, lazyConqueue, if (lazyConqueue) Conqueue.Lazy(Nil, Conqueue.empty, Nil) else Conqueue.empty)

  def size = conqueue.size

  def isEmpty = {
    leftIndex == leftStart && ConcOps.isEmptyConqueue(conqueue) && rightIndex == rightStart
  }

  def nonEmpty = !isEmpty

  private def leftEnsureSize(n: Int) {
    if (leftChunk.length < n) leftChunk = new Array[T](n)
  }

  private def rightEnsureSize(n: Int) {
    if (rightChunk.length < n) rightChunk = new Array[T](n)
  }

  private def pullLeft() {
    if (conqueue.nonEmpty) {
      val head = ConcOps.head(conqueue)
      conqueue = ConcOps.popHeadTop(conqueue)
      (head: @unchecked) match {
        case head: Chunk[T] =>
          leftChunk = head.array
          leftStart = head.size - 1
          leftIndex = -1
        case head: Single[T] =>
          leftChunk = new Array[T](k)
          leftChunk(k - 1) = head.x
          leftStart = k - 1
          leftIndex = k - 2
      }
    } else if (rightIndex > rightStart) {
      val rightMid = (rightStart + rightIndex + 1) / 2
      val n = rightMid - rightStart
      leftEnsureSize(n)
      System.arraycopy(rightChunk, rightStart, leftChunk, leftChunk.size - n, n)
      rightStart = rightMid
      leftStart = leftChunk.size - 1
      leftIndex = leftChunk.size - n - 1
    } else unsupported("empty")
  }

  def head: T = {
    if (leftIndex < leftStart) leftChunk(leftIndex)
    else {
      pullLeft()
      head
    }
  }

  private def pullRight() = {
    if (conqueue.nonEmpty) {
      val last = ConcOps.last(conqueue)
      conqueue = ConcOps.popLastTop(conqueue)
      (last: @unchecked) match {
        case last: Chunk[T] =>
          rightChunk = last.array
          rightStart = 0
          rightIndex = rightChunk.size
        case last: Single[T] =>
          rightChunk = new Array[T](k)
          rightChunk(0) = last.x
          rightStart = 0
          rightIndex = 1
      }
    } else if (leftIndex < leftStart) {
      val leftMid = (leftIndex + leftStart) / 2
      val n = leftStart - leftMid
      rightEnsureSize(n)
      System.arraycopy(leftChunk, leftMid, rightChunk, 0, n)
      leftStart = leftMid
      rightStart = 0
      rightIndex = n
    } else unsupported("empty")
  }

  def last: T = {
    if (rightIndex > rightStart) rightChunk(rightIndex)
    else {
      pullRight()
      last
    }
  }

  private def packLeft(): Unit = if (leftIndex < leftStart) {
    val sz = leftStart - leftIndex
    val chunk = {
      if (leftIndex == -1) leftChunk
      else ConcOps.copiedArray(leftChunk, leftIndex + 1, sz)
    }
    conqueue = ConcOps.pushHeadTop(conqueue, new Chunk(chunk, sz, k))
  }

  private def expandLeft() {
    packLeft()
    leftChunk = new Array[T](k)
    leftIndex = k - 1
    leftStart = k - 1
  }

  private def packRight(): Unit = if (rightIndex > rightStart) {
    val sz = rightIndex - rightStart
    val chunk = {
      if (rightStart == 0) rightChunk
      else ConcOps.copiedArray(rightChunk, rightStart, sz)
    }
    conqueue = ConcOps.pushLastTop(conqueue, new Chunk(chunk, sz, k))
  }

  private def expandRight() {
    packRight()
    rightChunk = new Array[T](k)
    rightIndex = 0
    rightStart = 0
  }

  def pushHead(elem: T): this.type = {
    if (leftIndex < 0) expandLeft()
    leftChunk(leftIndex) = elem
    leftIndex -= 1
    this
  }

  def popHead(): T = {
    if (leftIndex < leftStart) {
      leftIndex += 1
      val result = leftChunk(leftIndex)
      leftChunk(leftIndex) = null.asInstanceOf[T]
      result
    } else {
      pullLeft()
      popHead()
    }
  }

  def pushLast(elem: T): this.type = {
    if (rightIndex > rightChunk.size - 1) expandRight()
    rightChunk(rightIndex) = elem
    rightIndex += 1
    this
  }

  def popLast(): T = {
    if (rightIndex > rightStart) {
      rightIndex -= 1
      val result = rightChunk(rightIndex)
      rightChunk(rightIndex) = null.asInstanceOf[T]
      result
    } else {
      pullRight()
      popLast()
    }
  }

  def extractConqueue() = {
    packLeft()
    packRight()
    var result = conqueue
    conqueue = if (isLazy) Lazy(Nil, Conqueue.empty, Nil) else Conqueue.empty
    result
  }

}










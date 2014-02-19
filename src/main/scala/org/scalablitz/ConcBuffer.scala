package org.scalablitz



import scala.reflect.ClassTag



class ConcBuffer[@specialized(Byte, Char, Int, Long, Float, Double) T: ClassTag](val k: Int) {
  private var conc: Conc[T] = Conc.Empty
  private var chunk: Array[T] = new Array(k)
  private var lastSize: Int = 0

  final def +=(elem: T): this.type = {
    if (lastSize >= k) expand()
    chunk(lastSize) = elem
    lastSize += 1
    this
  }

  private def pack() {
    conc = Conc.appendTop(conc, new Conc.Chunk(chunk, lastSize, k))
  }

  private def expand() {
    pack()
    chunk = new Array(k)
    lastSize = 0
  }

  def extractConc(): Conc[T] = {
    pack()
    conc
  }
}


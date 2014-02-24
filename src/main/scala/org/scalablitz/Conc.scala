package org.scalablitz



import scala.annotation.unchecked
import scala.annotation.tailrec
import scala.annotation.switch
import scala.reflect.ClassTag



sealed trait Conc[@specialized(Byte, Char, Int, Long, Float, Double) +T] {
  def level: Int
  def size: Int
  def left: Conc[T]
  def right: Conc[T]
  def normalized = this
}


case class <>[+T](left: Conc[T], right: Conc[T]) extends Conc[T] {
  val level = 1 + math.max(left.level, right.level)
  val size = left.size + right.size
}


object Conc {

  sealed trait Leaf[T] extends Conc[T] {
    def left = unsupported("Leaves do not have children.")
    def right = unsupported("Leaves do not have children.")
  }
  
  case object Empty extends Leaf[Nothing] {
    def level = 0
    def size = 0
  }
  
  class Single[@specialized(Byte, Char, Int, Long, Float, Double) T](val x: T) extends Leaf[T] {
    def level = 0
    def size = 1
    override def toString = s"Single($x)"
  }
  
  class Chunk[@specialized(Byte, Char, Int, Long, Float, Double) T](val array: Array[T], val size: Int, val k: Int) extends Leaf[T] {
    def level = 0
    override def toString = s"Chunk(${array.take(5).mkString(", ")}, size, k)"
  }

}


sealed abstract class ConcRope[+T] extends Conc[T]


object ConcRope {

  case class Append[+T](left: Conc[T], right: Conc[T]) extends ConcRope[T] {
    val level = 1 + math.max(left.level, right.level)
    val size = left.size + right.size
    override def normalized = ConcOps.wrap(this, Conc.Empty)
  }
  
}


sealed abstract class Conqueue[+T] extends Conc[T] {
  def evaluated: Boolean
  def tail: Conqueue[T]
  def addIfUnevaluated[U >: T](stack: List[Conqueue.Spine[U]]): List[Conqueue.Spine[U]] = stack
}


object Conqueue {

  def empty = Tip(Zero)

  case class Lazy[+T](lstack: List[Spine[T]], queue: Conqueue[T], rstack: List[Spine[T]]) extends Conqueue[T] {
    def left = queue.left
    def right = queue.right
    def level = queue.level
    def size = queue.size
    def evaluated = unsupported("Undefined for lazy conqueue.")
    def tail = unsupported("Undefined for lazy conqueue.")
  }

  class Spine[+T](val lwing: Num[T], val rwing: Num[T], @volatile var evaluateTail: AnyRef) extends Conqueue[T] {
    lazy val tail: Conqueue[T] = {
      val t = (evaluateTail: @unchecked) match {
        case eager: Conqueue[T] => eager
        case suspension: Function0[_] => suspension().asInstanceOf[Conqueue[T]]
      }
      evaluateTail = null
      t
    }
    def evaluated = evaluateTail == null
    override def addIfUnevaluated[U >: T](stack: List[Conqueue.Spine[U]]) = if (!evaluated) this :: stack else stack
    def left = lwing
    def right = new <>(tail, rwing)
    val level: Int = 1 + math.max(lwing.level, math.max(tail.level, rwing.level))
    val size: Int = lwing.size + tail.size + rwing.size
  }

  case class Tip[+T](tip: Num[T]) extends Conqueue[T] {
    def left = tip.left
    def right = tip.right
    def level = tip.level
    def size = tip.size
    def evaluated = true
    def tail = unsupported("Undefined for the tip.")
  }

  sealed abstract class Num[+T] extends Conc[T] {
    def leftmost: Conc[T]
    def rightmost: Conc[T]
    def index: Int
  }

  case object Zero extends Num[Nothing] {
    def left = unsupported("Zero does not have children.")
    def right = unsupported("Zero does not have children.")
    def leftmost = unsupported("empty")
    def rightmost = unsupported("empty")
    def level: Int = 0
    def size: Int = 0
    def index = 0
  }

  case class One[T](_1: Conc[T]) extends Num[T] {
    def left = _1
    def right = Zero
    def leftmost = _1
    def rightmost = _1
    def level: Int = 1 + _1.level
    def size: Int = _1.size
    def index = 1
  }

  case class Two[T](_1: Conc[T], _2: Conc[T]) extends Num[T] {
    def left = _1
    def right = _2
    def leftmost = _1
    def rightmost = _2
    def level: Int = 1 + math.max(_1.level, _2.level)
    def size: Int = _1.size + _2.size
    def index = 2
  }

  case class Three[T](_1: Conc[T], _2: Conc[T], _3: Conc[T]) extends Num[T] {
    def left = _1
    def right = new <>(_2, _3)
    def leftmost = _1
    def rightmost = _3
    def level: Int = 1 + math.max(math.max(_1.level, _2.level), _3.level)
    def size: Int = _1.size + _2.size + _3.size
    def index = 3
  }

  case class Four[T](_1: Conc[T], _2: Conc[T], _3: Conc[T], _4: Conc[T]) extends Num[T] {
    def left = new <>(_1, _2)
    def right = new <>(_3, _4)
    def leftmost = _1
    def rightmost = _4
    def level: Int = 1 + math.max(math.max(_1.level, _2.level), math.max(_3.level, _4.level))
    def size: Int = _1.size + _2.size + _3.size + _4.size
    def index = 4
  }
}


object ConcOps {

  import Conc._
  import ConcRope._
  import Conqueue._

  def queueString[T](conq: Conqueue[T]): String = {
    val buffer = new StringBuffer

    def str(num: Num[T]): String = num match {
      case Zero => "Zero"
      case One(_1) => s"One(${_1.level})"
      case Two(_1, _2) => s"Two(${_1.level}, ${_2.level})"
      case Three(_1, _2, _3) => s"Three(${_1.level}, ${_2.level}, ${_3.level})"
      case Four(_1, _2, _3, _4) => s"Four(${_1.level}, ${_2.level}, ${_3.level}, ${_4.level})"
    }

    def traverse(rank: Int, indent: Int, conq: Conqueue[T]): Unit = (conq: @unchecked) match {
      case s: Spine[T] =>
        val lefts = str(s.lwing)
        val rights = str(s.rwing)
        val spines = "Spine(+)"
        buffer.append(" " * (indent - lefts.length) + lefts + " " + spines + " " + rights)
        buffer.append("\n")
        traverse(rank + 1, indent, s.tail)
      case Tip(tip) =>
        val tips = s"Tip(${str(tip)})"
        buffer.append(" " * (indent) + tips)
    }

    traverse(0, 40, conq)
    buffer.toString
  }

  def wrap[T](xs: Conc[T], ys: Conc[T]): Conc[T] = (xs: @unchecked) match {
    case Append(ws, zs) => wrap(ws, zs <> ys)
    case xs => xs <> ys
  }

  def foreach[@specialized(Byte, Char, Int, Long, Float, Double) T, @specialized(Byte, Char, Int, Long, Float, Double) U](xs: Conc[T], f: T => U): Unit = (xs: @unchecked) match {
    case left <> right =>
      foreach(left, f)
      foreach(right, f)
    case s: Single[T] =>
      f(s.x)
    case c: Chunk[T] =>
      val a = c.array
      val sz = c.size
      var i = 0
      while (i < sz) {
        f(a(i))
        i += 1
      }
    case Empty =>
    case Append(left, right) =>
      foreach(left, f)
      foreach(right, f)
    case conc: Conc[T] =>
      foreach(conc.left, f)
      foreach(conc.right, f)
  }

  def apply[@specialized(Byte, Char, Int, Long, Float, Double) T](xs: Conc[T], i: Int): T = (xs: @unchecked) match {
    case left <> _ if i < left.size =>
      apply(left, i)
    case left <> right =>
      apply(right, i - left.size)
    case s: Single[T] => s.x
    case c: Chunk[T] => c.array(i)
    case Append(left, _) if i < left.size =>
      apply(left, i)
    case Append(left, right) =>
      apply(right, i - left.size)
  }

  private def updatedArray[T: ClassTag](a: Array[T], i: Int, y: T, sz: Int): Array[T] = {
    val na = new Array[T](a.length)
    System.arraycopy(a, 0, na, 0, sz)
    na(i) = y
    na
  }

  def update[@specialized(Byte, Char, Int, Long, Float, Double) T: ClassTag](xs: Conc[T], i: Int, y: T): Conc[T] = (xs.normalized: @unchecked) match {
    case left <> right if i < left.size =>
      new <>(update(left, i, y), right)
    case left <> right =>
      val ni = i - left.size
      new <>(left, update(right, ni, y))
    case s: Single[T] =>
      new Single(y)
    case c: Chunk[T] =>
      new Chunk(updatedArray(c.array, i, y, c.size), c.size, c.k)
  }

  def concatTop[T](xs: Conc[T], ys: Conc[T]) = {
    if (xs == Empty) ys
    else if (ys == Empty) xs
    else concat(xs, ys)
  }

  private def concat[T](xs: Conc[T], ys: Conc[T]): Conc[T] = {
    val diff = ys.level - xs.level
    if (diff >= -1 && diff <= 1) new <>(xs, ys)
    else if (diff < -1) {
      if (xs.left.level >= xs.right.level) {
        val nr = concat(xs.right, ys)
        new <>(xs.left, nr)
      } else {
        val nl = new <>(xs.left, xs.right.left)
        val nr = concat(xs.right.right, ys)
        new <>(nl, nr)
      }
    } else {
      if (ys.right.level >= ys.left.level) {
        val nl = concat(xs, ys.left)
        new <>(nl, ys.right)
      } else {
        val nl = concat(xs, ys.left.left)
        val nr = new <>(ys.left.right, ys.right)
        new <>(nl, nr)
      }
    }
  }

  private def insertedArray[T: ClassTag](a: Array[T], from: Int, i: Int, y: T, sz: Int): Array[T] = {
    val na = new Array[T](sz + 1)
    System.arraycopy(a, from, na, 0, i)
    na(i) = y
    System.arraycopy(a, from + i, na, i + 1, sz - i)
    na
  }

  private def copiedArray[T: ClassTag](a: Array[T], from: Int, sz: Int): Array[T] = {
    val na = new Array[T](sz)
    System.arraycopy(a, from, na, 0, sz)
    na
  }

  def insert[@specialized(Byte, Char, Int, Long, Float, Double) T: ClassTag](xs: Conc[T], i: Int, y: T): Conc[T] = (xs.normalized: @unchecked) match {
    case left <> right if i < left.size =>
      insert(left, i, y) <> right
    case left <> right =>
      left <> insert(right, i - left.size, y)
    case s: Single[T] =>
      if (i == 0) new <>(new Single(y), xs)
      else new <>(xs, new Single(y))
    case c: Chunk[T] if c.size == c.k =>
      val a = c.array
      val sz = c.size
      val k = c.k
      if (i < k / 2) {
        val la = insertedArray(a, 0, i, y, k / 2)
        val ra = copiedArray(a, k / 2, k - k / 2)
        new <>(new Chunk(la, k / 2 + 1, k), new Chunk(ra, k - k / 2, k))
      } else {
        val la = copiedArray(a, 0, k / 2)
        val ra = insertedArray(a, k / 2, i - k / 2, y, k - k / 2 + 1)
        new <>(new Chunk(la, k / 2, k), new Chunk(ra, k - k / 2 + 1, k))
      }
    case c: Chunk[T] =>
      val a = c.array
      val sz = c.size
      val k = c.k
      new Chunk(insertedArray(a, 0, i, y, sz), sz + 1, k)
    case Empty =>
      new Single(y)
  }

  def appendTop[T](xs: Conc[T], ys: Leaf[T]): Conc[T] = (xs: @unchecked) match {
    case xs: Append[T] => append(xs, ys)
    case _ <> _ => new Append(xs, ys)
    case Empty => ys
    case xs: Leaf[T] => new <>(xs, ys)
  }

  @tailrec private def append[T](xs: Append[T], ys: Conc[T]): Conc[T] = {
    if (xs.right.level > ys.level) new Append(xs, ys)
    else {
      val zs = new <>(xs.right, ys)
      xs.left match {
        case ws @ Append(_, _) => append(ws, zs)
        case ws if ws.level <= zs.level => ws <> zs
        case ws => new Append(ws, zs)
      }
    }
  }

  def shakeLeft[T](xs: Conc[T]): Conc[T] = {
    if (xs.level <= 1) {
      //
      //       1       
      //    +--+--+    
      //    0     0    
      //
      xs
    } else if (xs.left.level >= xs.right.level) {
      //
      //                 n             
      //           +-----+-----+       
      //         n - 1       n - 1     
      //       +---+---+    (n - 2)    
      //     n - 2   n - 2             
      //    (n - 3) (n - 2)            
      //    (n - 2) (n - 3)            
      //
      xs
    } else if (xs.right.right.level >= xs.right.left.level) {
      //
      //            n                              n         
      //      +-----+-----+                  +-----+-----+   
      //    n - 2       n - 1      =>      n - 1       n - 2 
      //              +---+---+          +---+---+    (n - 2)
      //            n - 2   n - 2      n - 2   n - 2         
      //           (n - 3) (n - 2)            (n - 3)        
      //
      val nl = new <>(xs.left, xs.right.left)
      val nr = xs.right.right
      new <>(nl, nr)
    } else if (xs.left.left.level >= xs.left.right.level) {
      //
      //                    n                                      n                
      //          +---------+---------+                  +---------+---------+      
      //        n - 2               n - 1      =>      n - 1               n - 2    
      //      +---+---+           +---+---+          +---+---+           +---+---+  
      //    n - 3   n - 3       n - 2   n - 3      n - 3   n - 2       n - 3   n - 3
      //                      +---+---+                  +---+---+    (n - 4)       
      //                    n - 3   n - 3              n - 3   n - 3  (n - 3)       
      //                   (n - 3) (n - 4)                    (n - 3)               
      //                   (n - 4) (n - 3)                    (n - 4)               
      //
      //  OR:
      //
      //                    n                                      n                
      //          +---------+---------+                  +---------+---------+      
      //        n - 2               n - 1      =>      n - 1               n - 2    
      //      +---+---+           +---+---+          +---+---+           +---+---+  
      //    n - 3   n - 4       n - 2   n - 3      n - 3   n - 2       n - 3   n - 3
      //                      +---+---+                  +---+---+    (n - 4)       
      //                    n - 3   n - 3              n - 4   n - 3                
      //                   (n - 3) (n - 4)                    (n - 3)               
      //
      //  OR:
      //
      //                    n                                    n - 1              
      //          +---------+---------+                  +---------+---------+      
      //        n - 2               n - 1      =>      n - 2               n - 2    
      //      +---+---+           +---+---+          +---+---+           +---+---+  
      //    n - 3   n - 4       n - 2   n - 3      n - 3   n - 3       n - 3   n - 3
      //                      +---+---+                  +---+---+                  
      //                    n - 4   n - 3              n - 4   n - 4                
      //
      val nll = xs.left.left
      val nlr = new <>(xs.left.right, xs.right.left.left)
      val nl = new <>(nll, nlr)
      val nr = new <>(xs.right.left.right, xs.right.right)
      new <>(nl, nr)
    } else if (xs.right.left.left.level >= xs.right.left.right.level) {
      //
      //                    n                                             n                
      //          +---------+---------+                         +---------+---------+      
      //        n - 2               n - 1      =>             n - 1               n - 2    
      //      +---+---+           +---+---+              +------+------+        +---+---+  
      //    n - 4   n - 3       n - 2   n - 3          n - 2         n - 3    n - 3   n - 3
      //                      +---+---+              +---+---+      (n - 3)  (n - 4)       
      //                    n - 3   n - 3          n - 4   n - 3                           
      //                   (n - 3) (n - 4)                                                 
      //
      val nl = new <>(xs.left, xs.right.left.left)
      val nr = new <>(xs.right.left.right, xs.right.right)
      new <>(nl, nr)
    } else {
      //
      //                       n                                                    n - 1                 
      //          +------------+------------+                            +------------+------------+      
      //        n - 2                     n - 1      =>                n - 2                     n - 2    
      //      +---+---+                 +---+---+              +---------+---------+           +---+---+  
      //    n - 4   n - 3             n - 2   n - 3          n - 3               n - 3       n - 3   n - 3
      //          +---+---+         +---+---+              +---+---+           +---+---+                  
      //        n - 4   n - 4     n - 4   n - 3          n - 4   n - 4       n - 4   n - 4                
      //       (n - 4) (n - 5)                                  (n - 4)     (n - 5)                       
      //       (n - 5) (n - 4)                                  (n - 5)     (n - 4)                       
      //
      val nll = new <>(xs.left.left, xs.left.right.left)
      val nlr = new <>(xs.left.right.right, xs.right.left.left)
      val nl = new <>(nll, nlr)
      val nr = new <>(xs.right.left.right, xs.right.right)
      new <>(nl, nr)
    }
  }

  def shakeRight[T](xs: Conc[T]): Conc[T] = {
    if (xs.level <= 1) {
      //
      //       1       
      //    +--+--+    
      //    0     0    
      //
      xs
    } else if (xs.left.level <= xs.right.level) {
      //
      //             n                 
      //       +-----+-----+           
      //     n - 1       n - 1         
      //    (n - 2)    +---+---+       
      //             n - 2   n - 2     
      //            (n - 3) (n - 2)    
      //            (n - 2) (n - 3)    
      //
      xs
    } else if (xs.left.left.level >= xs.left.right.level) {
      //
      //                 n                      n            
      //           +-----+-----+          +-----+-----+      
      //         n - 1       n - 2  =>  n - 2       n - 1    
      //       +---+---+               (n - 2)    +---+---+  
      //     n - 2   n - 2                      n - 2   n - 2
      //    (n - 2) (n - 3)                    (n - 3)       
      //
      val nl = xs.left.left
      val nr = new <>(xs.left.right, xs.right)
      new <>(nl, nr)
    } else if (xs.right.right.level >= xs.right.left.level) {
      //
      //                    n                                      n                
      //          +---------+---------+                  +---------+---------+      
      //        n - 1               n - 2      =>      n - 2               n - 1    
      //      +---+---+           +---+---+          +---+---+           +---+---+  
      //    n - 3   n - 2       n - 3   n - 3      n - 3   n - 3       n - 2   n - 3
      //          +---+---+                               (n - 4)    +---+---+      
      //        n - 3   n - 3                             (n - 3)  n - 3   n - 3    
      //       (n - 4) (n - 3)                                    (n - 3)           
      //       (n - 3) (n - 4)                                    (n - 4)           
      //
      //  OR:
      //
      //                    n                                      n                
      //          +---------+---------+                  +---------+---------+      
      //        n - 1               n - 2      =>      n - 2               n - 1    
      //      +---+---+           +---+---+          +---+---+           +---+---+  
      //    n - 3   n - 2       n - 4   n - 3      n - 3   n - 3       n - 2   n - 3
      //          +---+---+                               (n - 4)    +---+---+      
      //        n - 3   n - 3                                      n - 3   n - 4    
      //       (n - 4) (n - 3)                                    (n - 3)           
      //
      //  OR:
      //
      //                    n                                    n - 1              
      //          +---------+---------+                  +---------+---------+      
      //        n - 1               n - 2      =>      n - 2               n - 2    
      //      +---+---+           +---+---+          +---+---+           +---+---+  
      //    n - 3   n - 2       n - 4   n - 3      n - 3   n - 3       n - 3   n - 3
      //          +---+---+                                          +---+---+      
      //        n - 3   n - 4                                      n - 4   n - 4    
      //
      val nl = new <>(xs.left.left, xs.left.right.left)
      val nrl = new <>(xs.left.right.right, xs.right.left)
      val nrr = xs.right.right
      val nr = new <>(nrl, nrr)
      new <>(nl, nr)
    } else if (xs.left.right.right.level >= xs.left.right.left.level) {
      //
      //                    n                                      n                       
      //          +---------+---------+                  +---------+---------+             
      //        n - 1               n - 2      =>      n - 2               n - 1           
      //      +---+---+           +---+---+          +---+---+        +------+------+      
      //    n - 3   n - 2       n - 3   n - 4      n - 3   n - 3    n - 3         n - 2    
      //          +---+---+                               (n - 4)  (n - 3)      +---+---+  
      //        n - 3   n - 3                                                 n - 3   n - 4
      //       (n - 4) (n - 3)                                                             
      //
      val nl = new <>(xs.left.left, xs.left.right.left)
      val nr = new <>(xs.left.right.right, xs.right)
      new <>(nl, nr)
    } else {
      //
      //                       n                                          n - 1                           
      //          +------------+------------+                  +------------+------------+                
      //        n - 1                     n - 2      =>      n - 2                     n - 2              
      //      +---+---+                 +---+---+          +---+---+           +---------+---------+      
      //    n - 3   n - 2             n - 3   n - 4      n - 3   n - 3       n - 3               n - 3    
      //          +---+---+         +---+---+                              +---+---+           +---+---+  
      //        n - 3   n - 4     n - 4   n - 4                          n - 4   n - 4       n - 4   n - 4
      //                         (n - 5) (n - 4)                                (n - 5)     (n - 4)       
      //                         (n - 4) (n - 5)                                (n - 4)     (n - 5)       
      //
      val nl = new <>(xs.left.left, xs.left.right.left)
      val nrl = new <>(xs.left.right.right, xs.right.left.left)
      val nrr = new <>(xs.right.left.right, xs.right.right)
      val nr = new <>(nrl, nrr)
      new <>(nl, nr)
    }
  }

  def pay[T](work: List[Spine[T]]): List[Spine[T]] = work match {
    case head :: rest =>
      // do 2 units of work
      val tail = head.tail
      if (tail.evaluated) pay(rest)
      else {
        val tailtail = tail.tail
        tailtail.addIfUnevaluated(rest)
      }
    case Nil =>
      // hoorah - nothing to do
      Nil
  }

  def pushHead[T](conq: Conqueue[T], c: Conc[T]): Conqueue[T] = {
    def noCarryPushHead(num: Num[T], c: Conc[T]): Num[T] = (num.index: @switch) match {
      case 0 =>
        One(c)
      case 1 =>
        val One(_1) = num
        Two(c, _1)
      case 2 =>
        val Two(_1, _2) = num
        Three(c, _1, _2)
      case _ =>
        invalid("Causes a carry.")
    }

    (conq: @unchecked) match {
      case s: Spine[T] =>
        if (s.lwing.index < 3) {
          new Spine(noCarryPushHead(s.lwing, c), s.rwing, s.tail)
        } else {
          val Three(_1, _2, _3) = s.lwing
          val nleft = Two(c, _1)
          val carry = _2 <> _3
          val ntail = (s.tail: @unchecked) match {
            case st: Spine[T] =>
              if (st.lwing.index < 3) pushHead(s.tail, carry)
              else if (st.lwing.index == 3) () => pushHead(s.tail, carry)
              else pushHead(s.tail, carry)
            case Tip(_) =>
              pushHead(s.tail, carry)
          }
          new Spine(nleft, s.rwing, ntail)
        }
      case Tip(tip) =>
        if (tip.index < 3) {
          Tip(noCarryPushHead(tip, c))
        } else {
          val Three(_1, _2, _3) = tip
          new Spine(Two(c, _1), Two(_2, _3), Tip(Zero))
        }
    }
  }

  def pushHeadTop[T](conq: Conqueue[T], leaf: Leaf[T]): Conqueue[T] = conq match {
    case Conqueue.Lazy(lstack, queue, rstack) =>
      val nqueue = pushHead(queue, leaf)
      val nlstack = pay(nqueue.addIfUnevaluated(lstack))
      val nrstack = pay(rstack)
      Conqueue.Lazy(nlstack, nqueue, nrstack)
    case _ =>
      pushHead(conq, leaf)
  }

  def popHead[T](conq: Conqueue[T]): Conqueue[T] = {
    ???
  }

  def head[T](conq: Conqueue[T]): Leaf[T] = {
    @tailrec def leftmost(c: Conc[T]): Leaf[T] = c match {
      case Empty => unsupported("empty")
      case l: Leaf[T] => l
      case _ <> _ => leftmost(c.left)
      case _ => invalid("Invalid conqueue state.")
    }

    (conq: @unchecked) match {
      case s: Spine[T] =>
        leftmost(s.lwing.leftmost)
      case Tip(tip) =>
        leftmost(tip.leftmost)
    }
  }

  def pushLast[T](conq: Conqueue[T], leaf: Leaf[T]): Conqueue[T] = {
    ???
  }

  def popLast[T](conq: Conqueue[T]): Conqueue[T] = {
    ???
  }

  def last[T](conq: Conqueue[T]): Leaf[T] = {
    @tailrec def rightmost(c: Conc[T]): Leaf[T] = c match {
      case Empty => unsupported("empty")
      case l: Leaf[T] => l
      case _ <> _ => rightmost(c.right)
      case _ => invalid("Invalid conqueue state: " + c.getClass.getSimpleName)
    }

    (conq: @unchecked) match {
      case s: Spine[T] =>
        rightmost(s.rwing.rightmost)
      case Tip(tip) =>
        rightmost(tip.rightmost)
    }
  }

}







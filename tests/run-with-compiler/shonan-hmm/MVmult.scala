
import dotty.tools.dotc.quoted.Toolbox._
import scala.quoted._

class MVmult[Idx, T, Unt](tring: Ring[T], vec: VecROp[Idx, T, Unt]) {
  private[this] val blas2 = new Blas2(tring, vec)
  import blas2._
  def mvmult(vout: OVec[Idx, T, Unt], a: Vec[Idx, Vec[Idx, T]], v: Vec[Idx, T]): Unt = vout := a * v
  override def toString(): String = s"MVmult($tring, $vec)"
}

object MVmult {
  def mvmult_p(vout: Array[Int], a: Array[Array[Int]], v: Array[Int]): Unit = {
    val n = vout.length
    val m = v.length

    val vout_ = OVec(n, (i, x: Int) => vout(i) = x)
    val a_ = Vec (n, i => Vec(m, j => a(i)(j)))
    val v_ = Vec (n, i => v(i))

    val MV = new MVmult[Int, Int, Unit](RingInt, new StaticVecR(RingInt))
    MV.mvmult(vout_, a_, v_)
  }

  def mvmult_c: Expr[(Array[Int], Array[Array[Int]], Array[Int]) => Unit] = '{
    (vout, a, v) => {
      val n = vout.length
      val m = v.length
      ~{
        val vout_ = OVec('(n), (i, x: Expr[Int]) => '(vout(~i) = ~x))
        val a_ = Vec('(n), (i: Expr[Int]) => Vec('(m), (j: Expr[Int]) => '{ a(~i)(~j) } ))
        val v_ = Vec('(m), (i: Expr[Int]) => '(v(~i)))

        val MV = new MVmult[Expr[Int], Expr[Int], Expr[Unit]](RingIntExpr, new VecRDyn)
        MV.mvmult(vout_, a_, v_)
      }
    }
  }

  def mvmult_mc(n: Int, m: Int): Expr[(Array[Int], Array[Array[Int]], Array[Int]) => Unit] = {
    val MV = new MVmult[Int, Expr[Int], Expr[Unit]](RingIntExpr, new VecRStaDim(RingIntExpr))
    '{
      (vout, a, v) => {
        if (~n.toExpr != vout.length) throw new IndexOutOfBoundsException(~n.toString.toExpr)
        if (~m.toExpr != v.length) throw new IndexOutOfBoundsException(~m.toString.toExpr)
        ~{
          val vout_ = OVec(n, (i, x: Expr[Int]) => '(vout(~i.toExpr) = ~x))
          val a_ = Vec(n, i => Vec(m, j => '{ a(~i.toExpr)(~j.toExpr) } ))
          val v_ = Vec(m, i => '(v(~i.toExpr)))

          MV.mvmult(vout_, a_, v_)
        }
      }
    }
  }

  def mvmult_ac(a: Array[Array[Int]]): Expr[(Array[Int], Array[Int]) => Unit] = {
    val n = a.length
    val m = a(0).length
    import Lifters._
    '{
      val arr = ~a.toExpr
      ~{
        val a2: Vec[PV[Int], Vec[PV[Int], PV[Int]]] = Vec(Sta(n), i => Vec(Sta(m), j => (i, j) match {
          case (Sta(i), Sta(j)) => Sta(a(i)(j))
          case (Sta(i), Dyn(j)) => Dyn('(arr(~i.toExpr)(~j)))
          case (i, j) => Dyn( '{ arr(~(Dyns.dyni(i)))(~(Dyns.dyni(j))) })
        }))
        mvmult_abs0(new RingIntPExpr, new VecRStaDyn(new RingIntPExpr))(a.length, a(0).length, a2)
      }
    }
  }

  def mvmult_opt(a: Array[Array[Int]]): Expr[(Array[Int], Array[Int]) => Unit] = {
    val n = a.length
    val m = a(0).length
    import Lifters._
    '{
      val arr = ~a.toExpr
      ~{
        val a2: Vec[PV[Int], Vec[PV[Int], PV[Int]]] = Vec(Sta(n), i => Vec(Sta(m), j => (i, j) match {
          case (Sta(i), Sta(j)) => Sta(a(i)(j))
          case (Sta(i), Dyn(j)) => Dyn('(arr(~i.toExpr)(~j)))
          case (i, j) => Dyn( '{ arr(~(Dyns.dyni(i)))(~(Dyns.dyni(j))) })
        }))
        mvmult_abs0(new RingIntOPExpr, new VecRStaDyn(new RingIntPExpr))(a.length, a(0).length, a2)
      }
    }
  }

  def mvmult_roll(a: Array[Array[Int]]): Expr[(Array[Int], Array[Int]) => Unit] = {
    val n = a.length
    val m = a(0).length
    import Lifters._
    '{
      val arr = ~a.toExpr
      ~{
        val a2: Vec[PV[Int], Vec[PV[Int], PV[Int]]] = Vec(Sta(n), i => Vec(Sta(m), j => (i, j) match {
          case (Sta(i), Sta(j)) => Sta(a(i)(j))
          case (Sta(i), Dyn(j)) => Dyn('(arr(~i.toExpr)(~j)))
          case (i, j) => Dyn( '{ arr(~(Dyns.dyni(i)))(~(Dyns.dyni(j))) })
        }))
        mvmult_abs0(new RingIntOPExpr, new VecRStaOptDynInt(new RingIntPExpr))(a.length, a(0).length, a2)
      }
    }
  }

  private def mvmult_abs0(ring: Ring[PV[Int]], vecOp: VecROp[PV[Int], PV[Int], Expr[Unit]])(n: Int, m: Int, a: Vec[PV[Int], Vec[PV[Int], PV[Int]]]): Expr[(Array[Int], Array[Int]) => Unit] = {
    '{
      (vout, v) => {
        if (~n.toExpr != vout.length) throw new IndexOutOfBoundsException(~n.toString.toExpr)
        if (~m.toExpr != v.length) throw new IndexOutOfBoundsException(~m.toString.toExpr)
        ~{
          val vout_ : OVec[PV[Int], PV[Int], Expr[Unit]] = OVec(Sta(n), (i, x) => '(vout(~Dyns.dyni(i)) = ~Dyns.dyn(x)))
          val v_ : Vec[PV[Int], PV[Int]] = Vec(Sta(m), i => Dyn('(v(~Dyns.dyni(i)))))
          val MV = new MVmult[PV[Int], PV[Int], Expr[Unit]](ring, vecOp)
          MV.mvmult(vout_, a, v_)
        }
      }
    }
  }


}

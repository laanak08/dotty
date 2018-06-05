package dotty.tools.dotc
package transform

import core._
import MegaPhase._
import Contexts.Context
import StdNames._
import Phases._
import ast._
import Trees._
import Flags._
import SymUtils._
import Symbols._
import SymDenotations._
import Types._
import Decorators._
import DenotTransformers._
import util.Positions._
import config.Printers.init.{ println => debug }
import Constants.Constant
import collection.mutable

object InitChecker {
  val name = "initChecker"
}

import DataFlowChecker._

/** This transform checks initialization is safe based on data-flow analysis
 *
 *  Partial values:
 *   - partial values cannot be used as full values
 *   - a partial value can only be assigned to uninitialized field of a partial value
 *   - selection on a partial value is an error, unless the accessed field is known to be fully initialized
 *
 *  Init methods:
 *   - methods called during initialization should be annotated with `@init` or non-overridable
 *   - an `@init` method should not call overridable non-init methods
 *   - an overriding or implementing `@init` may only access param fields or other init-methods on `this`
 *   - otherwise, it may access non-param fields on `this`
 *
 *  Partial values are defined as follows:
 *   - params with the type `T @partial`
 *   - `this` in constructor unless it's known to be fully initialized
 *   - `new C(args)`, if any argument is partial
 *   - `val x = rhs` where the right-hand-side is partial
 *
 *  TODO:
 *   - default arguments of partial/init methods
 *   - selection on ParamAccessors of partial value is fine if the param is not partial
 *   - handle tailrec calls during initialization (which captures `this`)
 *
 */
class InitChecker extends MiniPhase with IdentityDenotTransformer { thisPhase =>
  import tpd._

  override def phaseName: String = InitChecker.name

  override def transformDefDef(tree: tpd.DefDef)(implicit ctx: Context): tpd.Tree = {
    tree
  }

  override def transformTemplate(tree: Template)(implicit ctx: Context): Tree = {
    val cls = ctx.owner.asClass

    if (cls.hasAnnotation(defn.UncheckedAnnot)) return tree

    val env = classEnv(cls)
    val checker = new DataFlowChecker
    checker.indexLatents(tree.body, env)

    val res = checker.checkStats(tree.body, env)
    res.effects.foreach(_.report)
    env.nonInit.foreach { sym =>
      ctx.warning(s"field ${sym.name} is not initialized", sym.pos)
    }

    tree
  }
}

object DataFlowChecker {
  sealed trait Effect {
    def report(implicit ctx: Context): Unit = this match {
      case Member(sym, obj, pos)    =>
        ctx.warning(s"Select $sym on partial value ${obj.show}", pos)
      case Uninit(sym, pos)         =>
        ctx.warning(s"Reference to uninitialized value `${sym.name}`", pos)
      case OverrideRisk(sym, pos)     =>
        ctx.warning(s"`@scala.annotation.init` is recommended for abstract $sym for safe initialization", sym.pos)
        ctx.warning(s"Reference to $sym which could be overriden", pos)
      case Call(sym, effects, pos)  =>
        ctx.warning(s"The call to `${sym.name}` causes initialization problem", pos)
        effects.foreach(_.report)
      case Force(sym, effects, pos) =>
        ctx.warning(s"Forcing lazy val `${sym.name}` causes initialization problem", pos)
        effects.foreach(_.report)
      case Argument(sym, arg)       =>
        ctx.warning(s"Use partial value ${arg.show} as a full value to ${sym.show}", arg.pos)
      case CrossAssign(lhs, rhs)    =>
        ctx.warning(s"Assign partial value to a non-partial value", rhs.pos)
      case PartialNew(prefix, cls, pos)  =>
        ctx.warning(s"Cannot create $cls because the prefix `${prefix.show}` is partial", pos)
      case Instantiate(cls, effs, pos)  =>
        ctx.warning(s"Create instance results in initialization errors", pos)
        effs.foreach(_.report)
      case UseAbstractDef(sym, pos)  =>
         ctx.warning(s"`@scala.annotation.init` is recommended for abstract $sym for safe initialization", sym.pos)
         ctx.warning(s"Reference to abstract $sym which should be annotated with `@scala.annotation.init`", pos)
      case Latent(tree, effs)  =>
        effs.foreach(_.report)
        ctx.warning(s"Latent effects results in initialization errors", tree.pos)
      case RecCreate(cls, tree)  =>
        ctx.warning(s"Possible recursive creation of instance for ${cls.show}", tree.pos)
    }
  }
  case class Uninit(sym: Symbol, pos: Position) extends Effect                         // usage of uninitialized values
  case class OverrideRisk(sym: Symbol, pos: Position) extends Effect                   // calling methods that are not override-free
  case class Call(sym: Symbol, effects: Seq[Effect], pos: Position) extends Effect     // calling method results in error
  case class Force(sym: Symbol, effects: Seq[Effect], pos: Position) extends Effect    // force lazy val results in error
  case class Argument(fun: Symbol, arg: tpd.Tree) extends Effect                       // use of partial values as full values
  case class Member(sym: Symbol, obj: tpd.Tree, pos: Position) extends Effect          // select members of partial values
  case class CrossAssign(lhs: tpd.Tree, rhs: tpd.Tree) extends Effect                  // assign a partial values to non-partial value
  case class PartialNew(prefix: Type, cls: Symbol, pos: Position) extends Effect       // create new inner class instance while outer is partial
  case class Instantiate(cls: Symbol, effs: Seq[Effect], pos: Position) extends Effect // create new instance of in-scope inner class results in error
  case class UseAbstractDef(sym: Symbol, pos: Position) extends Effect                 // use abstract def during initialization, see override5.scala
  case class Latent(tree: tpd.Tree, effs: Seq[Effect]) extends Effect                  // problematic latent effects (e.g. effects of closures)
  case class RecCreate(cls: Symbol, tree: tpd.Tree) extends Effect                     // recursive creation of class

  object NewEx {
    def extract(tp: Type)(implicit ctx: Context): TypeRef = tp.dealias match {
      case tref: TypeRef => tref
      case AppliedType(tref: TypeRef, targs) => tref
    }

    def unapply(tree: tpd.Tree)(implicit ctx: Context): Option[(TypeRef, TermRef, List[List[tpd.Tree]])] = {
      val (fn, targs, vargss) = tpd.decomposeCall(tree)
      if (!fn.symbol.isConstructor || !tree.isInstanceOf[tpd.Apply]) None
      else {
        val Select(New(tpt), _) = fn
        Some((extract(tpt.tpe),  fn.tpe.asInstanceOf[TermRef], vargss))
      }
    }
  }

  def classEnv(cls: ClassSymbol)(implicit ctx: Context) = {
    val accessors = cls.paramAccessors.filterNot(x => x.isSetter)

    var noninit = Set[Symbol]()    // definitions that are not initialized
    var partial = Set[Symbol]()    // definitions that are partial initialized

    def isPartial(sym: Symbol) = sym.info.hasAnnotation(defn.PartialAnnot)

    def isConcreteField(sym: Symbol) =
      sym.isTerm && sym.is(AnyFlags, butNot = Deferred | Method | Local | Private)

    def isNonParamField(sym: Symbol) =
      sym.isTerm && sym.is(AnyFlags, butNot = Method | ParamAccessor | Lazy | Deferred)

    // partial fields of current class
    for (
      param <- accessors
      if isPartial(param)
    )
    partial += param

    // partial fields of super class
    for (
      parent <- cls.baseClasses.tail;
      decl <- parent.info.decls.toList
      if isConcreteField(decl) && isPartial(decl)
    )
    partial += decl

    // add current this
    partial += cls

    // non-initialized fields of current class
    for (
      decl <- cls.info.decls.toList
      if isNonParamField(decl)
    )
    noninit += decl

    (new TopEnv(cls)).fresh.setNonInit(noninit).setPartialSyms(partial).setLocals(noninit ++ partial)
  }

  type Effects = Vector[Effect]
  case class LatentInfo(fun: (Int => ValueInfo) => Res) extends ((Int => ValueInfo) => Res) {
    def apply(valInfoFn: Int => ValueInfo): Res = fun(valInfoFn)
  }

  case class ValueInfo(partial: Boolean = false, latentInfo: LatentInfo = null) {
    def isLatent = latentInfo != null
  }

  class Env(private var outer: Env) extends Cloneable {
    protected var _locals: Set[Symbol] = Set()
    protected var _nonInit: Set[Symbol] = Set()
    protected var _partialSyms: Set[Symbol] = Set()
    protected var _lazyForced: Set[Symbol] = Set()
    protected var _latentSyms: Map[Symbol, LatentInfo] = Map()

    def fresh: FreshEnv = new FreshEnv(this)

    def deepClone: Env = {
      val env = this.clone.asInstanceOf[Env]
      env.outer = outer.deepClone
      env
    }

    def currentClass: ClassSymbol = outer.currentClass

    def addLocal(sym: Symbol) = _locals += sym

    def isPartial(sym: Symbol): Boolean =
      if (_locals.contains(sym)) _partialSyms.contains(sym)
      else outer.isPartial(sym)
    def addPartial(sym: Symbol): Unit =
      if (_locals.contains(sym)) _partialSyms += sym
      else outer.addPartial(sym)
    def removePartial(sym: Symbol) =
      _partialSyms -= sym

    def isLatent(sym: Symbol): Boolean =
      if (_locals.contains(sym)) _latentSyms.contains(sym)
      else outer.isLatent(sym)
    def addLatent(sym: Symbol, info: LatentInfo): Unit =
      if (_locals.contains(sym)) _latentSyms += sym -> info
      else outer.addLatent(sym, info)
    def latentInfo(sym: Symbol): LatentInfo =
      if (_latentSyms.contains(sym)) _latentSyms(sym)
      else outer.latentInfo(sym)

    def isForced(sym: Symbol): Boolean =
      if (_locals.contains(sym)) _lazyForced.contains(sym)
      else outer.isForced(sym)
    def addForced(sym: Symbol): Unit =
      if (_locals.contains(sym)) _lazyForced += sym
      else outer.addForced(sym)

    def isNotInit(sym: Symbol): Boolean =
      if (_locals.contains(sym)) _nonInit.contains(sym)
      else outer.isNotInit(sym)
    def addInit(sym: Symbol): Unit =
       if (_locals.contains(sym)) _nonInit -= sym
       else outer.addInit(sym)
    def nonInit = _nonInit

    def join(env2: Env): Env = {
      _nonInit ++= env2.nonInit
      _lazyForced ++= env2._lazyForced
      _partialSyms ++= env2._partialSyms
      outer.join(env2.outer)
    }

    def initialized: Boolean =
      _nonInit.isEmpty &&
        (_partialSyms.isEmpty || _partialSyms == Set(currentClass)) &&
        outer.initialized
    def markInitialized: Unit = {
      assert(initialized)
      _partialSyms = Set()
      outer.markInitialized
    }

    override def toString: String =
      (if (outer != null) outer.toString + "\n" else "") ++
      s"""~------------ $currentClass -------------
          ~| locals:  ${_locals}
          ~| not initialized:  ${_nonInit}
          ~| partial initialized: ${_partialSyms}
          ~| lazy forced:  ${_lazyForced}
          ~| latent symbols: ${_latentSyms.keys}"""
      .stripMargin('~')
  }

  class TopEnv(_cls: ClassSymbol) extends Env(null) {
    override def currentClass = _cls

    override def deepClone: Env = this
    override def join(env2: Env) = {
      assert(this `eq` env2)
      this
    }

    override def isPartial(sym: Symbol)    = false
    override def addPartial(sym: Symbol)   = throw new Exception(s"add partial ${sym} to top env")
    override def removePartial(sym: Symbol)= throw new Exception(s"remove partial ${sym} from top env")

    override def isLatent(sym: Symbol)     = false
    override def addLatent(sym: Symbol, effs: LatentInfo) = throw new Exception(s"add latent ${sym} to top env")
    override def latentInfo(sym: Symbol): LatentInfo = throw new Exception(s"$sym is not latent")

    override def isForced(sym: Symbol)     = false
    override def addForced(sym: Symbol)    = throw new Exception(s"add forced ${sym} to top env")

    override def isNotInit(sym: Symbol)    = false
    override def addInit(sym: Symbol)      = throw new Exception(s"add init ${sym} to top env")

    override def initialized: Boolean      = true
    override def markInitialized           = ()
  }

  class FreshEnv(outer: Env) extends Env(outer) {
    def setPartialSyms(partialSyms: Set[Symbol]): this.type = { this._partialSyms = partialSyms; this }
    def setNonInit(nonInit: Set[Symbol]): this.type = { this._nonInit = nonInit; this }
    def setLazyForced(lazyForced: Set[Symbol]): this.type = { this._lazyForced = lazyForced; this }
    def setLocals(locals: Set[Symbol]): this.type = { this._locals = locals; this }
  }

  case class Res(var effects: Effects = Vector.empty, var valueInfo: ValueInfo = ValueInfo()) {
    def force(valInfofn: Int => ValueInfo): Res = if (isLatent) valueInfo.latentInfo(valInfofn) else Res()
    def isLatent  = valueInfo.isLatent
    def isPartial = valueInfo.partial

    def +=(eff: Effect): Unit = effects = effects :+ eff
    def ++=(effs: Effects) = effects ++= effs

    def join(res2: Res): Res =
      Res(
        effects = res2.effects ++ this.effects,
        valueInfo = ValueInfo(
          partial = res2.isPartial || this.isPartial,
          latentInfo = LatentInfo {
            (fn: Int => ValueInfo) => {
              val resA = this.force(fn)
              val resB = res2.force(fn)
              resA.join(resB)
            }
          }
        )
      )

    override def toString: String =
      s"""~Res(
          ~| effects = ${if (effects.isEmpty) "()" else effects.mkString("\n|    - ", "\n|    - ", "")}
          ~| partial = $isPartial
          ~| latent  = $isLatent
          ~)"""
      .stripMargin('~')
  }
}

class DataFlowChecker {

  import tpd._

  var depth: Int = 0
  val indentTab = " "

  def trace[T](msg: String, env: Env)(body: => T) = {
    indentedDebug(s"==> ${pad(msg)}?")
    indentedDebug(env.toString)
    depth += 1
    val res = body
    depth -= 1
    indentedDebug(s"<== ${pad(msg)} = ${pad(res.toString)}")
    res
  }

  def padding = indentTab * depth

  def pad(s: String, padFirst: Boolean = false) =
    s.split("\n").mkString(if (padFirst) padding else "", "\n" + padding, "")

  def indentedDebug(msg: String) = debug(pad(msg, padFirst = true))

  def checkForce(sym: Symbol, tree: Tree, env: Env)(implicit ctx: Context): Res =
    if (sym.is(Lazy) && !env.isForced(sym)) {
      env.addForced(sym)
      val res = env.latentInfo(sym)(i => null)

      if (res.isPartial) env.addPartial(sym)
      if (res.isLatent) env.addLatent(sym, res.valueInfo.latentInfo)

      if (res.effects.nonEmpty) res.copy(effects = Vector(Force(sym, res.effects, tree.pos)))
      else res
    }
    else {
      val valueInfo = ValueInfo(
        partial = env.isPartial(sym),
        latentInfo = if (env.isLatent(sym)) env.latentInfo(sym) else null
      )
      Res(valueInfo = valueInfo)
    }

  def checkParams(sym: Symbol, paramInfos: List[Type], args: List[Tree], env: Env, force: Boolean)(implicit ctx: Context): (Res, Vector[ValueInfo]) = {
    def isParamPartial(index: Int) = paramInfos(index).hasAnnotation(defn.PartialAnnot)

    var effs = Vector.empty[Effect]
    var infos = Vector.empty[ValueInfo]
    var partial = false

    args.zipWithIndex.foreach { case (arg, index) =>
      val res = apply(env, arg)
      effs ++= res.effects
      partial = partial || res.isPartial
      infos = infos :+ res.valueInfo

      if (res.isLatent && force) {
        val effs2 = res.force(i => ValueInfo())            // latent values are not partial
        if (effs2.effects.nonEmpty) {
          partial = true
          if (!isParamPartial(index)) effs = effs :+ Latent(arg, effs2.effects)
        }
      }
      if (res.isPartial && !isParamPartial(index) && force) effs = effs :+ Argument(sym, arg)
    }

    (Res(effects = effs, valueInfo = ValueInfo(partial = partial)), infos)
  }

  def checkNew(tree: Tree, tref: TypeRef, init: TermRef, argss: List[List[tpd.Tree]], env: Env)(implicit ctx: Context): Res = {
    val paramInfos = init.widen.paramInfoss.flatten
    val args = argss.flatten

    val (res1, _) = checkParams(tref.symbol, paramInfos, args, env, force = true)

    if (tref.symbol == env.currentClass) {
      res1 += RecCreate(tref.symbol, tree)
      return res1
    }
    else if (!isPartial(tref.prefix, env) || isSafeVirtualAccess(tref, env)) return res1

    if (!isLexicalRef(tref, env)) {
      res1 += PartialNew(tref.prefix, tref.symbol, tree.pos)
      res1
    }
    else {
      val latentInfo = env.latentInfo(tref.symbol)
      val res2 = latentInfo(i => null)                // TODO: propagate params to init
      if (res2.effects.nonEmpty) res1 += Instantiate(tref.symbol, res2.effects, tree.pos)
      res1.copy(valueInfo = ValueInfo(partial = true))
    }
  }

  def checkApply(tree: tpd.Tree, fun: Tree, args: List[Tree], env: Env)(implicit ctx: Context): Res = {
    val res1 = apply(env, fun)

    val paramInfos = fun.tpe.widen.asInstanceOf[MethodType].paramInfos
    val (res2, valueInfos) = checkParams(fun.symbol, paramInfos, args, env, force = !res1.isLatent)

    var effs = res1.effects ++ res2.effects

    if (res1.isLatent) {
      val res3 = res1.force(i => valueInfos(i))
      if (res3.effects.nonEmpty) effs = effs :+ Latent(tree, res3.effects)

      res3.copy(effects = effs)
    }
    else Res(effects = effs)
  }

  def checkSelect(tree: Select, env: Env)(implicit ctx: Context): Res = {
    val res = apply(env, tree.qualifier)

    if (res.isPartial)
      res += Member(tree.symbol, tree.qualifier, tree.pos)

    res
  }

  /** return the top-level local term within `cls` refered by `tp`, NoType otherwise.
   *
   *  There are following cases:
   *   - select on this: `C.this.x`
   *   - select on super: `C.super[Q].x`
   *   - local ident: `x`
   *   - select on self: `self.x` (TODO)
   */
  def localRef(tp: Type, env: Env)(implicit ctx: Context): Type = tp match {
    case TermRef(ThisType(tref), _) if tref.symbol.isContainedIn(env.currentClass) => tp
    case TermRef(SuperType(ThisType(tref), _), _) if tref.symbol.isContainedIn(env.currentClass) => tp
    case ref @ TermRef(NoPrefix, _) if ref.symbol.isContainedIn(env.currentClass) => ref
    case TermRef(tp: TermRef, _) => localRef(tp, env)
    case _ => NoType
  }

  object NamedTypeEx {
    def unapply(tp: Type)(implicit ctx: Context): Option[(Type, Symbol)] = tp match {
      case ref: TermRef => Some(ref.prefix -> ref.symbol)
      case ref: TypeRef => Some(ref.prefix -> ref.symbol)
      case _ => None
    }
  }

  /** Does the NamedType refer to a symbol defined within `cls`? */
  def isLexicalRef(tp: NamedType, env: Env)(implicit ctx: Context): Boolean =
    ctx.owner.isContainedIn(tp.symbol.owner) || tp.symbol.isContainedIn(ctx.owner)

  /** Is the NamedType a reference to safe member defined in the parent of `cls`?
   *
   *  A member access is safe in the following cases:
   *  - a non-lazy, non-deferred field where the primary constructor takes no partial values
   *  - a method marked as `@init`
   *  - a class marked as `@init`
   */
  def isSafeVirtualAccess(tp: NamedType, env: Env)(implicit ctx: Context): Boolean =
    tp.symbol.owner.isClass &&
      (env.currentClass.isSubClass(tp.symbol.owner) ||
         env.currentClass.givenSelfType.classSymbols.exists(_.isSubClass(tp.symbol.owner))) &&
      (
        tp.symbol.isTerm && tp.symbol.is(AnyFlags, butNot = Method | Lazy | Deferred) && !hasPartialParam(tp.symbol.owner, env) ||
        tp.symbol.hasAnnotation(defn.InitAnnot) || tp.symbol.hasAnnotation(defn.PartialAnnot) ||
        isDefaultGetter(tp.symbol) || (env.initialized && env.currentClass.is(Final))
      )

  // TODO: default methods are not necessarily safe, if they call other methods
  def isDefaultGetter(sym: Symbol)(implicit ctx: Context) = sym.name.is(NameKinds.DefaultGetterName)

  def hasPartialParam(clazz: Symbol, env: Env)(implicit ctx: Context): Boolean =
    env.currentClass.paramAccessors.exists(_.hasAnnotation(defn.PartialAnnot))

  def isPartial(tp: Type, env: Env)(implicit ctx: Context): Boolean = tp match {
    case tmref: TermRef             => env.isPartial(tmref.symbol)
    case ThisType(tref)             => env.isPartial(tref.symbol)
    case SuperType(thistp, _)       => isPartial(thistp, env)        // super is partial if `thistp` is partial
    case _                          => false
  }

  def checkTermRef(tree: Tree, env: Env)(implicit ctx: Context): Res = {
    indentedDebug(s"is ${tree.show} local ? = " + localRef(tree.tpe, env).exists)
    val ref: TermRef = localRef(tree.tpe, env) match {
      case NoType         => return Res()
      case tmref: TermRef => tmref
    }

    val sym = ref.symbol

    var effs = Vector.empty[Effect]

    if (isLexicalRef(ref, env)) {
      if (env.isNotInit(sym)) effs = effs :+ Uninit(sym, tree.pos)

      if (sym.is(Lazy)) {                // a forced lazy val could be partial and latent
        val res2 = checkForce(sym, tree, env)
        return res2.copy(effects = effs ++ res2.effects)
      }
      else if (sym.is(Method)) {
        if (!(sym.hasAnnotation(defn.InitAnnot) || sym.isEffectivelyFinal || isDefaultGetter(sym)))
          effs = effs :+ OverrideRisk(sym, tree.pos)

        if (sym.info.isInstanceOf[ExprType]) {       // parameter-less call
          val latentInfo = env.latentInfo(sym)
          val res2 = latentInfo(i => null)

          return {
            if (res2.effects.nonEmpty) res2.copy(effects = Vector(Call(sym, effs ++ res2.effects, tree.pos)))
            else res2.copy(effects = effs)
          }
        }
        else {
          return Res(effects = effs, valueInfo = ValueInfo(false, env.latentInfo(sym)))
        }
      }
      else if (sym.is(Deferred) && !sym.hasAnnotation(defn.InitAnnot) && sym.owner == env.currentClass) {
        effs = effs :+ UseAbstractDef(sym, tree.pos)
      }
    }
    else if (isPartial(ref.prefix, env) && !isSafeVirtualAccess(ref, env)) {
      effs = effs :+ Member(sym, tree, tree.pos)
    }

    Res(
      effects = effs,
      valueInfo = ValueInfo(
        partial = env.isPartial(sym),
        latentInfo = if (env.isLatent(sym)) env.latentInfo(sym) else null
      )
    )
  }

  def checkClosure(sym: Symbol, tree: Tree, env: Env)(implicit ctx: Context): Res = {
    Res(
      valueInfo = ValueInfo(
        partial = false,
        latentInfo = env.latentInfo(sym)
      )
    )
  }

  def checkIf(tree: If, env: Env)(implicit ctx: Context): Res = {
    val If(cond, thenp, elsep) = tree

    val res1: Res = apply(env, cond)

    val envClone = env.deepClone
    val res2: Res = apply(env, thenp)
    val res3: Res = apply(envClone, elsep)

    env.join(envClone)

    res2.copy(effects = res1.effects ++ res2.effects).join(res3)
  }

  def checkValDef(vdef: ValDef, env: Env)(implicit ctx: Context): Res = {
    val res1 = apply(env, vdef.rhs)

    if (!tpd.isWildcardArg(vdef.rhs) && !vdef.rhs.isEmpty)
      env.addInit(vdef.symbol)     // take `_` as uninitialized, otherwise it's initialized

    if (res1.isPartial) {
      if (env.initialized) // fully initialized
        env.markInitialized
      else
        env.addPartial(vdef.symbol)
    }

    if (res1.isLatent)
      env.addLatent(vdef.symbol, res1.valueInfo.latentInfo)

    res1.copy(valueInfo = ValueInfo())
  }

  def checkStats(stats: List[Tree], env: Env)(implicit ctx: Context): Res =
    stats.foldLeft(Res()) { (acc, stat) =>
      indentedDebug(s"acc = ${pad(acc.toString)}")
      val res1 = apply(env, stat)
      acc.copy(effects = acc.effects ++ res1.effects)
    }

  def checkBlock(tree: Block, env: Env)(implicit ctx: Context): Res = {
    val env2 = env.fresh
    indexLatents(tree.stats, env2)

    val res1 = checkStats(tree.stats, env2)
    val res2 = apply(env2, tree.expr)

    res2.copy(effects = res1.effects ++ res2.effects)
  }

  // TODO: method call should compute fix point
  protected var _methChecking: Set[Symbol] = Set()
  def isChecking(sym: Symbol)   = _methChecking.contains(sym)
  def checking[T](sym: Symbol)(fn: => T) = {
    _methChecking += sym
    val res = fn
    _methChecking -= sym
    res
  }

  def indexLatents(stats: List[Tree], env: Env)(implicit ctx: Context): Unit = stats.foreach {
    case ddef: DefDef if ddef.symbol.is(AnyFlags, butNot = Accessor) =>
      val (init: List[List[ValDef]], last: List[ValDef]) = ddef.vparamss match {
        case Nil => (Nil, Nil)
        case init :+ last => (init, last)
      }

      val zero = LatentInfo { valInfoFn =>
        if (isChecking(ddef.symbol)) {
          debug(s"recursive call of ${ddef.symbol} found during initialization of ${env.currentClass}")
          Res()
        }
        else {
          val env2 = env.fresh
          last.zipWithIndex.foreach { case (param: ValDef, index: Int) =>
            val paramInfo = valInfoFn(index)
            env2.addLocal(param.symbol)
            if (paramInfo.isLatent) env2.addLatent(param.symbol, paramInfo.latentInfo)
            if (paramInfo.partial) env2.addPartial(param.symbol)
          }

          checking(ddef.symbol) { apply(env2, ddef.rhs)(ctx.withOwner(ddef.symbol)) }
        }
      }

      // TODO: handle multiple block
      //
      // val latentInfo = init.foldRight(zero) { (params, latentInfo) =>
      //   LatentInfo { valInfoFn =>
      //     if (sharedEnv == null) sharedEnv = env.fresh

      //     params.zipWithIndex.foreach { case (param, index) =>
      //       val paramInfo = valInfoFn(index)
      //       sharedEnv.addLocal(param.symbol)
      //       if (paramInfo.isLatent) sharedEnv.addLatent(param.symbol, paramInfo.latentInfo)
      //       if (paramInfo.partial) sharedEnv.addPartial(param.symbol)
      //     }

      //     Res(valueInfo = ValueInfo(latentInfo = latentInfo))
      //   }
      // }
      env.addLocal(ddef.symbol)
      env.addLatent(ddef.symbol, zero)
    case vdef: ValDef if vdef.symbol.is(Lazy)  =>
      val latent = LatentInfo { valInfoFn =>
        if (isChecking(vdef.symbol)) {
          debug(s"recursive forcing of lazy ${vdef.symbol} found during initialization of ${env.currentClass}")
          Res()
        }
        else checking(vdef.symbol) {
          apply(env, vdef.rhs)
        }
      }
      env.addLocal(vdef.symbol)
      env.addLatent(vdef.symbol, latent)
    case tdef: TypeDef if tdef.isClassDef  =>
      val env2 = env.fresh
      val latent = LatentInfo { valInfoFn =>
        if (isChecking(tdef.symbol)) {
          debug(s"recursive creation of ${tdef.symbol} found during initialization of ${env.currentClass}")
          Res()
        }
        else checking(tdef.symbol) {
          apply(env2, tdef.rhs)(ctx.withOwner(tdef.symbol))
        }
      }
      env.addLocal(tdef.symbol)
      env.addLatent(tdef.symbol, latent)
    case mdef: MemberDef =>
      env.addLocal(mdef.symbol)
    case _ =>
  }

  def apply(env: Env, tree: Tree)(implicit ctx: Context): Res = trace("checking " + tree.show, env)(tree match {
    case tmpl: Template =>
      val stats = tmpl.body.filter {
        case vdef : ValDef  =>
          !vdef.symbol.hasAnnotation(defn.ScalaStaticAnnot)
        case stat =>
          true
      }
      val env2 = env.fresh
      indexLatents(stats, env2)
      checkStats(stats, env2)
    case vdef : ValDef if !vdef.symbol.is(Lazy) =>
      checkValDef(vdef, env)
    case _: DefTree =>  // ignore other definitions
      Res()
    case Closure(_, meth, _) =>
      checkClosure(meth.symbol, tree, env)
    case tree: Ident if tree.symbol.isTerm =>
      checkTermRef(tree, env)
    case tree @ Select(prefix @ (This(_) | Super(_, _)), _) if tree.symbol.isTerm =>
      checkTermRef(tree, env)
    case tree @ NewEx(tref, init, argss) =>
      checkNew(tree, tref, init, argss, env)
    case tree @ Select(prefix, _) if tree.symbol.isTerm =>
      checkSelect(tree, env)
    case tree @ This(_) =>
      if (env.isPartial(tree.symbol) && !env.initialized) Res(valueInfo = ValueInfo(partial = true))
      else Res()
    case tree @ Super(qual, mix) =>
      if (env.isPartial(qual.symbol) && !env.initialized) Res(valueInfo = ValueInfo(partial = true))
      else Res()
    case tree @ If(cond, thenp, elsep) =>
      checkIf(tree, env)
    case tree @ Apply(fun, args) =>
      checkApply(tree, fun, args, env)
    case tree @ Assign(lhs @ (Ident(_) | Select(This(_), _)), rhs) =>
      val resRhs = apply(env, rhs)

      if (!resRhs.isPartial || env.isPartial(lhs.symbol) || env.isNotInit(lhs.symbol)) {
        if (env.isNotInit(lhs.symbol)) env.addInit(lhs.symbol)
        if (!resRhs.isPartial) env.removePartial(lhs.symbol)
        else env.addPartial(lhs.symbol)
      }
      else resRhs += CrossAssign(lhs, rhs)

      resRhs.copy(valueInfo = ValueInfo())
    case tree @ Assign(lhs @ Select(prefix, _), rhs) =>
      val resLhs = apply(env, prefix)
      val resRhs = apply(env, rhs)

      val res = Res(effects = resLhs.effects ++ resRhs.effects)

      if (resRhs.isPartial && !resLhs.isPartial)
        res += CrossAssign(lhs, rhs)

      res
    case tree @ Block(stats, expr) =>
      checkBlock(tree, env)
    case Typed(expr, tpd) =>
      apply(env, expr)
    case _ =>
      Res()
  })
}
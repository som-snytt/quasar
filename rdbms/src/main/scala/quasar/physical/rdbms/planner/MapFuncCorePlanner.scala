/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.rdbms.planner

import slamdata.Predef._
import slamdata.Predef.{Eq => _}
import quasar.Data
import quasar.DataCodec
import DataCodec.Precise.{DateKey, IntervalKey, TimeKey, TimestampKey}
import quasar.Planner._
import quasar.physical.rdbms.planner.sql.{Contains, StrLower, StrUpper, Substring, Search, StrSplit, ArrayConcat, SqlExpr => SQL}
import quasar.physical.rdbms.planner.sql.SqlExpr._
import quasar.physical.rdbms.planner.sql.SqlExpr.Case._
import quasar.qscript.{MapFuncsCore => MFC, _}
import quasar.std.StdLib.string.{dateRegex, timeRegex, timestampRegex}
import matryoshka._
import matryoshka.implicits._
import quasar.physical.rdbms.model.{BoolCol, DecCol, IntCol, StringCol}

import scalaz._
import Scalaz._
import quasar.physical.rdbms.planner.sql.Indirections._


class MapFuncCorePlanner[T[_[_]]: BirecursiveT: ShowT, F[_]:Applicative:PlannerErrorME]
    extends Planner[T, F, MapFuncCore[T, ?]] {

  val undefined: T[SQL] = SQL.Null[T[SQL]]().embed

  def str(s: String): T[SQL] = SQL.Constant[T[SQL]](Data.Str(s)).embed

  // TODO consider reusing "project()"
  def toKeyValue(expr: T[SQL], key: String): T[SQL] = {
    val nr: Refs[T[SQL]] = expr.project match {
      case Refs(elems, m) => Refs(elems :+ str(key), m)
      case _ => Refs(Vector(expr, str(key)), deriveIndirection(expr))
    }
    nr.embed
  }

  def datetime(a1: T[SQL], key: String, regex: Regex): T[SQL] = {
    Case.build(
      WhenThen(
        IsNotNull(toKeyValue(a1, key)).embed,
        a1),
      WhenThen(
        RegexMatches(a1, str(regex.regex), caseInsensitive = false).embed,
        Obj(List(str(key) -> a1)).embed)
    )(
      Else(SQL.Null[T[SQL]].embed)
    ).embed
  }

  private def project(fSrc: T[SQL], fKey: T[SQL]) = fSrc.project match {
    case SQL.Refs(list, m) => SQL.Refs(list :+ fKey, m).embed.η[F]
    case _ => SQL.Refs(Vector(fSrc, fKey), deriveIndirection(fSrc)).embed.η[F] // _12
  }

  def plan: AlgebraM[F, MapFuncCore[T, ?], T[SQL]] = {
    case MFC.Constant(ejson) => SQL.Constant[T[SQL]](ejson.cata(Data.fromEJson)).embed.η[F]
    case MFC.Undefined() =>  undefined.η[F]
    case MFC.JoinSideName(n) =>  notImplemented("JoinSideName", this)
    case MFC.Length(f) => notImplemented("Length", this)
    case MFC.Date(f) => datetime(f, DateKey, dateRegex.r).η[F]
    case MFC.Time(f) =>  datetime(f, TimeKey, timeRegex.r).η[F]
    case MFC.Timestamp(f) => datetime(f, TimestampKey, timestampRegex.r).η[F]
    case MFC.Interval(f) =>
      Case.build(
        WhenThen(IsNotNull(toKeyValue(f, IntervalKey)).embed, f)
      )(
        Else(SQL.Null[T[SQL]].embed)
      ).embed.η[F]
    case MFC.StartOfDay(f) =>  notImplemented("StartOfDay", this)
    case MFC.TemporalTrunc(p, f) =>  notImplemented("TemporalTrunc", this)
    case MFC.TimeOfDay(f) =>  notImplemented("TimeOfDay", this)
    case MFC.ToTimestamp(f) =>  notImplemented("ToTimestamp", this)
    case MFC.ExtractCentury(f) =>  notImplemented("ExtractCentury", this)
    case MFC.ExtractDayOfMonth(f) =>  notImplemented("ExtractDayOfMonth", this)
    case MFC.ExtractDecade(f) =>  notImplemented("ExtractDecade", this)
    case MFC.ExtractDayOfWeek(f) =>  notImplemented("ExtractDayOfWeek", this)
    case MFC.ExtractDayOfYear(f) =>  notImplemented("ExtractDayOfYear", this)
    case MFC.ExtractEpoch(f) =>  notImplemented("ExtractEpoch", this)
    case MFC.ExtractHour(f) =>  notImplemented("ExtractHour", this)
    case MFC.ExtractIsoDayOfWeek(f) =>  notImplemented("ExtractIsoDayOfWeek", this)
    case MFC.ExtractIsoYear(f) =>  notImplemented("ExtractIsoYear", this)
    case MFC.ExtractMicroseconds(f) => notImplemented("ExtractMicroseconds", this)
    case MFC.ExtractMillennium(f) =>  notImplemented("ExtractMillennium", this)
    case MFC.ExtractMilliseconds(f) =>  notImplemented("ExtractMilliseconds", this)
    case MFC.ExtractMinute(f) =>  notImplemented("ExtractMinute", this)
    case MFC.ExtractMonth(f) =>  notImplemented("ExtractMonth", this)
    case MFC.ExtractQuarter(f) =>  notImplemented("ExtractQuarter", this)
    case MFC.ExtractSecond(f) =>  notImplemented("ExtractSecond", this)
    case MFC.ExtractWeek(f) =>  notImplemented("ExtractWeek", this)
    case MFC.ExtractYear(f) =>  notImplemented("ExtractYear", this)
    case MFC.Now() =>  notImplemented("Now", this)
    case MFC.Negate(f) =>  SQL.Neg[T[SQL]](f).embed.η[F]
    case MFC.Add(f1, f2) =>  SQL.NumericOp[T[SQL]]("+", f1, f2).embed.η[F]
    case MFC.Multiply(f1, f2) => SQL.NumericOp[T[SQL]]("*", f1, f2).embed.η[F]
    case MFC.Subtract(f1, f2) =>  SQL.NumericOp[T[SQL]]("-", f1, f2).embed.η[F]
    case MFC.Divide(f1, f2) => SQL.NumericOp[T[SQL]]("/", f1, f2).embed.η[F]
    case MFC.Modulo(f1, f2) => SQL.Mod[T[SQL]](f1, f2).embed.η[F]
    case MFC.Power(f1, f2) =>  SQL.Pow[T[SQL]](f1, f2).embed.η[F]
    case MFC.Not(f) =>  notImplemented("Not", this)
    case MFC.Eq(f1, f2) => SQL.Eq[T[SQL]](f1, f2).embed.η[F]
    case MFC.Neq(f1, f2) => SQL.Neq[T[SQL]](f1, f2).embed.η[F]
    case MFC.Lt(f1, f2) =>  SQL.Lt[T[SQL]](f1, f2).embed.η[F]
    case MFC.Lte(f1, f2) => SQL.Lte[T[SQL]](f1, f2).embed.η[F]
    case MFC.Gt(f1, f2) =>  SQL.Gt[T[SQL]](f1, f2).embed.η[F]
    case MFC.Gte(f1, f2) => SQL.Gte[T[SQL]](f1, f2).embed.η[F]
    case MFC.IfUndefined(f1, f2) => SQL.IfNull.build(f1, f2).embed.η[F]
    case MFC.And(f1, f2) =>  SQL.And[T[SQL]](f1, f2).embed.η[F]
    case MFC.Or(f1, f2) =>  SQL.Or[T[SQL]](f1, f2).embed.η[F]
    case MFC.Between(f1, f2, f3) =>  notImplemented("Between", this)
    case MFC.Cond(fCond, fThen, fElse) =>  notImplemented("Cond", this)
    case MFC.Within(f1, f2) =>  SQL.BinaryFunction(Contains, f1, f2).embed.η[F]
    case MFC.Lower(f) =>  SQL.UnaryFunction(StrLower, f).embed.η[F]
    case MFC.Upper(f) =>  SQL.UnaryFunction(StrUpper, f).embed.η[F]
    case MFC.Bool(f) =>  SQL.Coercion(BoolCol, f).embed.η[F]
    case MFC.Integer(f) =>  SQL.Coercion(IntCol, f).embed.η[F]
    case MFC.Decimal(f) =>  SQL.Coercion(DecCol, f).embed.η[F]
    case MFC.Null(f) =>  SQL.Null[T[SQL]].embed.η[F]
    case MFC.ToString(f) =>  SQL.Coercion(StringCol, f).embed.η[F]
    case MFC.Search(fStr, fPattern, fIsCaseInsensitive) =>
      fIsCaseInsensitive.project match {
        case Constant(Data.Bool(insensitive)) =>
          SQL.RegexMatches(fStr, fPattern, insensitive).embed.η[F]
        case _ =>
          SQL.TernaryFunction(Search, fStr, fPattern, fIsCaseInsensitive).embed.η[F]
      }
    case MFC.Substring(fStr, fFrom, fCount) => SQL.TernaryFunction(Substring, fStr, fFrom, fCount).embed.η[F]
    case MFC.Split(fStr, fDelim) => SQL.BinaryFunction(StrSplit, fStr, fDelim).embed.η[F]
    case MFC.MakeArray(f) =>  SQL.ToArray(f).embed.η[F]
    case MFC.MakeMap(key, value) =>
      key.project match {
              case Constant(Data.Str(keyStr)) =>
                SQL.ExprWithAlias[T[SQL]](value, keyStr).embed.η[F]
              case other =>
                notImplemented(s"MakeMap with key = $other", this)
            }
    case MFC.ConcatArrays(f1, f2) =>  SQL.BinaryFunction(ArrayConcat, f1, f2).embed.η[F]
    case MFC.ConcatMaps(f1, f2) =>
      (f1.project, f2.project) match {
        case (ExprWithAlias(e1, a1), ExprWithAlias(e2, a2)) =>

          val patmat: PartialFunction[String, (IndirectionType, Indirection)] = {
            case `a1` => (Field, deriveIndirection(e1))
            case `a2` => (Field, deriveIndirection(e2))
          }

          val meta = Branch(patmat.orElse {
            s => Default match {
              case Branch(m, _) => {
                m(s)
              }
            }
          }, s"$a1 -> (Dot, ${deriveIndirection(e1).shows}), $a2 -> (Dot, ${deriveIndirection(e2).shows})")

          ExprPair[T[SQL]](f1, f2, meta).embed.η[F]

        case (o1, o2) =>
          ExprPair[T[SQL]](f1, f2, Default).embed.η[F]
      }

    case MFC.ProjectIndex(fSrc, fKey) => project(fSrc, fKey)
    case MFC.ProjectKey(fSrc, fKey) => project(fSrc, fKey)
    case MFC.DeleteKey(fSrc, fField) =>   notImplemented("DeleteKey", this)
    case MFC.Range(fFrom, fTo) =>  notImplemented("Range", this)
    case MFC.Guard(f1, fPattern, f2, ff3) => f2.η[F]
    case MFC.TypeOf(f) => notImplemented("TypeOf", this)
    case other => unexpected(other.getClass.getName, this)
  }
}

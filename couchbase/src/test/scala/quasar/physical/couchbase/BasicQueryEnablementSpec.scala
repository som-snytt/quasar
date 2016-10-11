/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.physical.couchbase

import quasar.Predef._
import quasar.{Planner => _, _}
import quasar.contrib.pathy.{ADir, PathSegment}
import quasar.effect.MonotonicSeq
import quasar.fp._, eitherT._, free._, ski.ι
import quasar.physical.couchbase.planner._
import quasar.qscript.{Map => _, Read => _, _}, MapFuncs._
import quasar.sql.CompilerHelpers
import quasar.physical.couchbase.N1QL._
import quasar.physical.couchbase.fs.queryfile._
import quasar.physical.couchbase.planner._, Planner._

import eu.timepit.refined.auto._
import matryoshka._, Recursive.ops._
import org.specs2.execute.Pending
import org.specs2.specification.core.Fragment
import pathy.Path._
import scalaz._, Scalaz._
import scalaz.concurrent.Task

// NB: These tests are incredibly fragile and should be removed once the
// implementation is sufficient to support existing query integration tests.
class BasicQueryEnablementSpec
  extends Qspec
  with QScriptHelpers
  with CompilerHelpers {

  sequential

  def compileLogicalPlan(query: String): Fix[LogicalPlan] =
    compile(query).map(Optimizer.optimize).fold(e => scala.sys.error(e.shows), ι)

  def lc[S[_]]: DiscoverPath.ListContents[Plan[S, ?]] =
    Kleisli[Id, ADir, Set[PathSegment]](listContents >>> (_ + FileName("beer-sample").right))
      .transform(λ[Id ~> Plan[S, ?]](_.point[Plan[S, ?]]))
      .run

  type Eff[A] = (MonotonicSeq :/: Task)#M[A]

  def n1qlFromSql2(sql2: String): String =
    lpLcToN1qlString[Eff](compileLogicalPlan(sql2), lc)
      .run.run.map(_._2)
      .foldMap(MonotonicSeq.fromZero.unsafePerformSync :+: reflNT[Task])
      .unsafePerformSync
      .fold(
        e => scala.sys.error(e.shows),
        outerN1qlFromString)

  def n1qlFromQS(qs: Fix[QS]): String =
    EitherT(qs.cataM(Planner[Free[MonotonicSeq, ?], QS].plan).run.run.map(_._2))
      .flatMap(q => EitherT.right(N1QL.outerN1ql[Free[MonotonicSeq, ?]](q)))
      .fold(e => scala.sys.error(e.shows), ι)
      .foldMap(MonotonicSeq.fromZero.unsafePerformSync)
      .unsafePerformSync

  def testSql2ToN1ql(sql2: String, n1ql: String): Fragment =
    sql2 in (n1qlFromSql2(sql2) must_= n1ql)

  def testSql2ToN1qlPending(sql2: String, p: Pending): Fragment =
    sql2 in p

  "SQL² to N1QL" should {
    testSql2ToN1ql(
      "select * from `beer-sample`",
      """select value v from (select value _0 from (select value ifmissing(v.`value`, v) from `beer-sample` v) as _0) as v""")

    testSql2ToN1ql(
      "select name from `beer-sample`",
      """select value v from (select value object_add({}, "name", _0.name) from (select value ifmissing(v.`value`, v) from `beer-sample` v) as _0) as v""")

    testSql2ToN1qlPending(
      "select name, type from `beer-sample`",
      pending("ConcatMaps implementation"))

    testSql2ToN1ql(
      "select name from `beer-sample` offset 1",
      """select value v from (select value _4 from (select value _2[_1[0]:] from (select value (select value object_add({}, "name", _5.name) from (select value ifmissing(v.`value`, v) from `beer-sample` v) as _5) from (select value (select value [])) as _0) as _2 let _1 = (select value 1 let _7 = (select value []))) as _3 unnest _3 _4) as v""")

    testSql2ToN1ql(
      "select name from `beer-sample` limit 1",
      """select value v from (select value _4 from (select value _2[0:_1[0]] from (select value (select value object_add({}, "name", _5.name) from (select value ifmissing(v.`value`, v) from `beer-sample` v) as _5) from (select value (select value [])) as _0) as _2 let _1 = (select value 1 let _7 = (select value []))) as _3 unnest _3 _4) as v""")

    testSql2ToN1qlPending(
      "select name from `beer-sample` order by name",
      pending("#1411"))

    testSql2ToN1qlPending(
      """select name from `beer-sample` where name = "Brasserie de Silly"""",
      todo)

    testSql2ToN1ql(
      "select count(*) from `beer-sample`",
      """select value v from (select value object_add({}, "0", count(_0)) from (select value ifmissing(v.`value`, v) from `beer-sample` v) as _0) as v""")

    testSql2ToN1ql(
      "select count(name) from `beer-sample`",
      """select value v from (select value object_add({}, "0", count(_0.name)) from (select value ifmissing(v.`value`, v) from `beer-sample` v) as _0) as v""")

    testSql2ToN1ql(
      "select geo.lat + geo.lon from `beer-sample`",
      """select value v from (select value object_add({}, "0", (_0.geo.lat + _0.geo.lon)) from (select value ifmissing(v.`value`, v) from `beer-sample` v) as _0) as v""")
  }

  "QScript to N1QL" should {

    "convert a squashed read" in {
      // "select * from foo"
      val qs =
        chain(
           ReadR(rootDir </> file("foo")),
           QC.inj(LeftShift((),
             HoleF,
             Free.point(RightSide))))

      val n1ql = n1qlFromQS(qs)

      n1ql must_= "select value v from (select value _0 from (select value ifmissing(v.`value`, v) from `foo` v) as _1 unnest _1 as _0) as v"
    }

    "convert a simple projection" in {
      // "select zed from foo"
      val qs =
        chain(
          ReadR(rootDir </> file("foo")),
          QC.inj(LeftShift((),
            HoleF,
            ProjectFieldR(Free.point(RightSide), StrLit("zed")))))

      val n1ql = n1qlFromQS(qs)

      n1ql must_= "select value v from (select value zed from (select value _0 from (select value ifmissing(v.`value`, v) from `foo` v) as _1 unnest _1 as _0) as _2) as v"
    }

    "read followed by a map" in {
      // "select (a + b) from foo"
      val qs =
        chain(
          ReadR(rootDir </> file("foo")),
          QC.inj(qscript.Map(
            (),
            Free.roll(Add(
              ProjectFieldR(HoleF, StrLit("a")),
              ProjectFieldR(HoleF, StrLit("b")))))))

      val n1ql = n1qlFromQS(qs)

      n1ql must_= "select value v from (select value (_0.a + _0.b) from (select value ifmissing(v.`value`, v) from `foo` v) as _0) as v"
    }

    "convert a basic reduction wrapped in an object" in {
      // "select sum(height) from person"
      val qs =
        chain(
          ReadR(rootDir </> file("person")),
          QC.inj(LeftShift((),
            ProjectFieldR(HoleF, StrLit("person")),
            ProjectFieldR(
              Free.point(RightSide),
              StrLit("height")))),
          QC.inj(Reduce(
            (),
            NullLit(), // reduce on a constant bucket, which is normalized to Null
            List(ReduceFuncs.Sum[FreeMap](HoleF)),
            Free.roll(MakeMap(StrLit("0"), Free.point(ReduceIndex(0)))))))

      val n1ql = n1qlFromQS(qs)

      n1ql must_= """select value v from (select value object_add({}, "0", sum(_10)) from (select value height from (select value _0 from (select value ifmissing(v.`value`, v) from `person` v) as _1 unnest _1 as _0) as _2) as _10) as v"""
    }

    "convert a flatten array" in {
      // "select loc[:*] from zips",
      val qs =
        chain(
          ReadR(rootDir </> file("zips")),
          QC.inj(LeftShift((),
            ProjectFieldR(HoleF, StrLit("zips")),
            ProjectFieldR(
              Free.point(RightSide),
              StrLit("loc")))),
          QC.inj(LeftShift((),
            HoleF,
            Free.roll(MakeMap(StrLit("loc"), Free.point(RightSide))))))

      val n1ql = n1qlFromQS(qs)

      n1ql must_= """select value v from (select value object_add({}, "loc", (select value _10 from (select value loc from (select value _0 from (select value ifmissing(v.`value`, v) from `zips` v) as _1 unnest _1 as _0) as _2) as _11 unnest _11 as _10))) as v"""
    }

    "convert a filter" in {
      // "select * from foo where bar between 1 and 10"
      val qs =
        chain(
          ReadR(rootDir </> file("foo")),
          QC.inj(LeftShift((),
            HoleF,
            Free.point(RightSide))),
          QC.inj(Filter((),
            Free.roll(Between(
              ProjectFieldR(HoleF, StrLit("bar")),
              IntLit(1),
              IntLit(10))))))

      val n1ql = n1qlFromQS(qs)

      todo
    }
  }

}

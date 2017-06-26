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

package quasar.physical.sparkcore.fs.elastic


import slamdata.Predef._
import quasar.contrib.pathy._
import quasar.fs._,
  ManageFile._,
  MoveScenario._,
  FileSystemError._,
  PathError._
// import quasar.effect._
import quasar.fp.free._
import quasar.fs.impl.ensureMoveSemantics

import pathy._, Path._
import scalaz._, Scalaz._

object managefile {

  def chrooted[S[_]](prefix: ADir)(implicit
    s1: ElasticCall.Ops[S]
  ): ManageFile ~> Free[S, ?] =
    flatMapSNT(interpret) compose chroot.manageFile[ManageFile](prefix)

  def interpret[S[_]](implicit
    s0: ElasticCall.Ops[S]
  ): ManageFile ~> Free[S, ?] =
    new (ManageFile ~> Free[S, ?]) {
      def apply[A](mf: ManageFile[A]): Free[S, A] = mf match {
        case Move(FileToFile(sf, df), semantics) =>
          (ensureMoveSemantics[Free[S, ?]](sf, df, pathExists[S] _, semantics).toLeft(()) *>
            moveFile(sf, df).liftM[FileSystemErrT]).run
        case Move(DirToDir(sd, dd), semantics) =>
          (ensureMoveSemantics[Free[S, ?]](sd, dd, pathExists _, semantics).toLeft(()) *>
            moveDir(sd, dd).liftM[FileSystemErrT]).run
        case Delete(path) => delete(path)
        case TempFile(near) => tempFile(near)
      }
    }

  def pathExists[S[_]](path: APath)(implicit 
    elastic: ElasticCall.Ops[S]
    ): Free[S, Boolean] = 
    refineType(path).fold(
      d => elastic.listIndeces.map(_.contains(dir2Index(d))),
      f => {
        val IndexType(index, typ) = file2ES(f)
        elastic.typeExists(index, typ)
      }
    )

  def moveFile[S[_]](sf: AFile, df: AFile)(implicit
    elastic: ElasticCall.Ops[S]
  ): Free[S, Unit] = for {
    sourceIndexType              <- file2ES(sf).point[Free[S, ?]]
    destinationIndexType         <- file2ES(df).point[Free[S, ?]]
    IndexType(srcIndex, srcType) = sourceIndexType
    IndexType(dstIndex, dstType) = destinationIndexType
    dstIndexExists               <- elastic.indexExists(dstIndex)
    dstTypeExists                <- elastic.typeExists(dstIndex, dstType)
    _                            <- if(dstTypeExists) elastic.deleteType(dstIndex, dstType)
                                    else if(!dstIndexExists) elastic.createIndex(dstIndex)
                                    else ().point[Free[S, ?]]
    _                            <- elastic.copy(sourceIndexType, destinationIndexType)
    _                            <- elastic.deleteType(srcIndex, srcType)
  } yield ()


  // TODO_ES
  def moveDir[S[_]](sd: ADir, dd: ADir)(implicit
    cass: ElasticCall.Ops[S]
  ): Free[S, Unit] = ().point[Free[S, ?]]

  def delete[S[_]](path: APath)(implicit
    elastic: ElasticCall.Ops[S]
  ): Free[S, FileSystemError \/ Unit] =
    refineType(path).fold(d => deleteDir(d),f => deleteFile(f))

  private def deleteFile[S[_]](file: AFile)(implicit
    elastic: ElasticCall.Ops[S]
  ): Free[S, FileSystemError \/ Unit] = for {
    indexType <- file2ES(file).point[Free[S, ?]]
    IndexType(index, typ) = indexType
    exists <- elastic.typeExists(index, typ)
    res <- if(exists) elastic.deleteType(index, typ).map(_.right) else pathErr(pathNotFound(file)).left[Unit].point[Free[S, ?]]
  } yield res

  private def deleteDir[S[_]](dir: ADir)(implicit
    elastic: ElasticCall.Ops[S]
  ): Free[S, FileSystemError \/ Unit] = for {
    indices <- elastic.listIndeces
    index = dir2Index(dir)
    result <- if(indices.isEmpty) pathErr(pathNotFound(dir)).left[Unit].point[Free[S, ?]] else {
      indices.filter(_.startsWith(index)).map(elastic.deleteIndex(_)).sequence.as(().right[FileSystemError])
    }
  } yield result

  def tempFile[S[_]](near: APath): Free[S, FileSystemError \/ AFile] = {
    val randomFileName = s"q${scala.math.abs(scala.util.Random.nextInt(9999))}"
    val aDir: ADir = refineType(near).fold(d => d, fileParent(_))
      (aDir </> file(randomFileName)).right[FileSystemError].point[Free[S, ?]]
  }

}

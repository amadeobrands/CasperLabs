package io.casperlabs.storage.dag

import java.io.Serializable

import cats.{Applicative, Monad}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.metrics.Metered
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import simulacrum.typeclass

import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.util.control.NoStackTrace
import cats.Functor
import com.github.ghik.silencer.silent
import io.casperlabs.storage.dag.DagRepresentation.ObservedValidatorBehavior.{
  Empty,
  Equivocated,
  Honest
}
import shapeless.tag.@@

trait DagStorage[F[_]] {

  /** Doesn't guarantee to return immutable representation. */
  def getRepresentation: F[DagRepresentation[F]]

  def checkpoint(): F[Unit]

  def clear(): F[Unit]

  def close(): F[Unit]

  /** Insert a block into the DAG and update the latest messages.
    * In the presence of eras, the block only affects the latest messages
    * of the era which the block is part of. To detect equivocations
    * or the tips, the caller needs to look at multiple eras along the tree.
    */
  private[storage] def insert(block: Block): F[DagRepresentation[F]]
}

object DagStorage {
  def apply[F[_]](implicit B: DagStorage[F]): DagStorage[F] = B

  trait MeteredDagStorage[F[_]] extends DagStorage[F] with Metered[F] {

    abstract override def getRepresentation: F[DagRepresentation[F]] =
      incAndMeasure("representation", super.getRepresentation)

    abstract override def insert(block: Block): F[DagRepresentation[F]] =
      incAndMeasure("insert", super.insert(block))

    abstract override def checkpoint(): F[Unit] =
      incAndMeasure("checkpoint", super.checkpoint())
  }

  trait MeteredDagRepresentation[F[_]] extends DagRepresentation[F] with Metered[F] {

    abstract override def children(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("children", super.children(blockHash))

    abstract override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("justificationToBlocks", super.justificationToBlocks(blockHash))

    abstract override def lookup(blockHash: BlockHash): F[Option[Message]] =
      incAndMeasure("lookup", super.lookup(blockHash))

    abstract override def contains(blockHash: BlockHash): F[Boolean] =
      incAndMeasure("contains", super.contains(blockHash))

    abstract override def topoSort(
        startBlockNumber: Long,
        endBlockNumber: Long
    ): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure(
        "topoSort",
        super
          .topoSort(startBlockNumber, endBlockNumber)
      )

    abstract override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure("topoSort", super.topoSort(startBlockNumber))

    abstract override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure("topoSortTail", super.topoSortTail(tailLength))
  }

  trait MeteredTipRepresentation[F[_]] extends TipRepresentation[F] with Metered[F] {
    abstract override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      incAndMeasure("latestMessageHash", super.latestMessageHash(validator))

    abstract override def latestMessage(validator: Validator): F[Set[Message]] =
      incAndMeasure("latestMessage", super.latestMessage(validator))

    abstract override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      incAndMeasure("latestMessageHashes", super.latestMessageHashes)

    abstract override def latestMessages: F[Map[Validator, Set[Message]]] =
      incAndMeasure("latestMessages", super.latestMessages)
  }
}

trait TipRepresentation[F[_]] {
  def latestMessageHash(validator: Validator): F[Set[BlockHash]]
  def latestMessage(validator: Validator): F[Set[Message]]
  def latestMessageHashes: F[Map[Validator, Set[BlockHash]]]
  def latestMessages: F[Map[Validator, Set[Message]]]
}

trait EraTipRepresentation[F[_]] extends TipRepresentation[F] {
  // TODO: These methods should move here from DagRepresentationRich,
  // to make sure we never try to detect equivocators on the global
  // representation, however for that we need lots of updates in
  // the casper codebase.

  // Equivocation will be defined as the set of validators who
  // equivocated in the eras between the key block and the current era,
  // but it will be up to the caller to aggregate this information,
  // i.e. to decide how far to look back, before forgiveness kicks in.

  // Since latest messages are restricted to this era, when looking
  // to build a new block, one has to reduce the tips of multiple eras
  // into the final set of childless blocks.

  // The justifications of a new block can be chosen as the reduced set of:
  // * the latest messages in the era,
  // * the parent block candidate hashes, and
  // * the justifications of the parent block candidates.

  def getEquivocators(implicit A: Applicative[F]): F[Set[Validator]] =
    getEquivocations.map(_.keySet)

  def getEquivocations(implicit A: Applicative[F]): F[Map[Validator, Set[Message]]] =
    latestMessages.map(_.filter(_._2.size > 1))
}

@typeclass trait DagLookup[F[_]] {
  def lookup(blockHash: BlockHash): F[Option[Message]]
  def contains(blockHash: BlockHash): F[Boolean]

  def lookupBlockUnsafe(blockHash: BlockHash)(implicit MT: MonadThrowable[F]): F[Message.Block] =
    lookupUnsafe(blockHash).flatMap(
      msg =>
        Try(msg.asInstanceOf[Message.Block]) match {
          case Success(value) => MT.pure(value)
          case Failure(_)     =>
            // PrettyPrinter is not visible here.
            val hashEncoded = Base16.encode(blockHash.toByteArray).take(10)
            MT.raiseError(
              new Exception(
                s"$hashEncoded was expected to be a Block but was a Ballot."
              ) with NoStackTrace
            )
        }
    )

  def lookupUnsafe(blockHash: BlockHash)(implicit MT: MonadThrowable[F]): F[Message] =
    lookup(blockHash) flatMap {
      MonadThrowable[F].fromOption(
        _,
        new NoSuchElementException(
          s"DAG store was missing ${Base16.encode(blockHash.toByteArray)}."
        )
      )
    }
}

trait DagRepresentation[F[_]] extends DagLookup[F] {
  def children(blockHash: BlockHash): F[Set[BlockHash]]

  /** Return blocks which have a specific block in their justifications. */
  def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]]

  /** Return block summaries with ranks in the DAG between start and end, inclusive. */
  def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockInfo]]

  /** Return block summaries with ranks of blocks in the DAG from a start index to the end. */
  def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]]

  def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]]

  /** Get a global representation, which can be used in:
    * 1) naive casper mode, without eras
    * 2) in the gossiping, when nodes ask each other for their latest blocks
    *
    * Latest messages would be that of any era which is considered active,
    * so that pull based gossiping can pull ballots of eras still being finalized
    * as well as the child era which is already started, but we stop returning
    * records for eras that have already finished (their last ballots are no longer
    * relevant tips).
    *
    * This will not reflect equivocations in the presence of parallel eras.
    *
    * Doesn't guarantee to return immutable representation.
    */
  def latestGlobal: F[TipRepresentation[F]]

  /** Get a representation restricted to a given era, which mean anyone
    * with more than 1 entry in their latest messages must have equivocated
    * in *this* era. If they equivocated in an ancestor era, that has to be
    * detected separately in the application layer by walking backward on
    * the era tree, according to the forgiveness settings.
    *
    * Messages in sibling eras are invisible to each other.
    *
    * The DAG itself, i.e. the parent child relationships are unaffected.
    */
  def latestInEra(keyBlockHash: BlockHash): F[EraTipRepresentation[F]]
}

@silent("is never used")
object DagRepresentation {
  type Validator = ByteString

  implicit class DagRepresentationRich[F[_]: Monad](
      dagRepresentation: DagRepresentation[F]
  ) {
    def getMainChildren(
        blockHash: BlockHash
    ): F[List[BlockHash]] =
      dagRepresentation
        .children(blockHash)
        .flatMap(
          _.toList
            .filterA(
              child =>
                dagRepresentation.lookup(child).map {
                  // make sure child's main parent's hash equal to `blockHash`
                  case Some(blockSummary) => blockSummary.parents.head == blockHash
                  case None               => false
                }
            )
        )

    // Returns a set of validators that this node has seen equivocating.
    def getEquivocators: F[Set[Validator]] =
      getEquivocations.map(_.keySet)

    // Returns a mapping between equivocators and their messages.
    def getEquivocations: F[Map[Validator, Set[Message]]] =
      latestMessages.map(_.filter(_._2.size > 1))

    def latestMessages: F[Map[Validator, Set[Message]]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessages)

    // NOTE: These extension methods are here so the Naive-Casper codebase doesn't have to do another
    // step (i.e. `.latestGlobal.flatMap { tip => ... }`)  but in Highway we should first specify the era.

    def latestMessagesInEra(keyBlockHash: ByteString): F[Map[Validator, Set[Message]]] =
      dagRepresentation.latestInEra(keyBlockHash).flatMap(_.latestMessages)

    def getEquivocatorsInEra(keyBlockHash: ByteString): F[Set[Validator]] =
      dagRepresentation.latestInEra(keyBlockHash).flatMap(_.getEquivocators)

    def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessageHash(validator))

    def latestMessage(validator: Validator): F[Set[Message]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessage(validator))

    def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessageHashes)

    // Returns latest messages from honest validators
    def latestMessagesHonestValidators: F[Map[Validator, Message]] =
      latestMessages.map { latestMessages =>
        latestMessages.collect {
          case (v, messages) if messages.size == 1 =>
            (v, messages.head)
        }
      }
  }

  def apply[F[_]](implicit ev: DagRepresentation[F]): DagRepresentation[F] = ev

  final class EraObservedBehavior[A] private (
      val data: Map[ByteString, Map[Validator, ObservedValidatorBehavior[A]]]
  ) {
    // Returns set of equivocating validators that are visible in the j-past-cone
    // of the era.
    def equivocatorsVisibleInEras(
        keyBlockHashes: Set[ByteString]
    ): Set[Validator] =
      data
        .collect {
          case (keyBlockHash, lms) if keyBlockHashes(keyBlockHash) =>
            lms.filter(_._2.isEquivocated).keySet
        }
        .flatten
        .toSet

    def keyBlockHashes: Set[ByteString] = data.keySet

    def validatorsInEra(keyBlockHash: ByteString): Set[Validator] =
      data.get(keyBlockHash).fold(Set.empty[Validator])(_.keySet)

//    def latestMessagesInEra[F[_]: MonadThrowable: DagLookup](
//        keyBlockHash: ByteString
//    )(implicit ev: A =:= ByteString): F[Map[Validator, Set[Message]]] =
//      data.get(keyBlockHash) match {
//        case None => Map.empty[Validator, Set[Message]].pure[F]
//        case Some(lms) =>
//          lms.toList
//            .traverse {
//              case (v, observedBehavior) =>
//                val lm: F[Set[Message]] = observedBehavior match {
//                  case Empty => Set.empty[Message].pure[F]
//                  case Honest(hash) =>
//                    DagLookup[F].lookupUnsafe(hash.asInstanceOf[ByteString]).map(Set(_))
//                  case Equivocated(m1, m2) =>
//                    MonadThrowable[F].map2(
//                      DagLookup[F].lookupUnsafe(m1.asInstanceOf[ByteString]),
//                      DagLookup[F].lookupUnsafe(m2.asInstanceOf[ByteString])
//                    ) { case (a, b) => Set(a, b) }
//
//                }
//                lm.map(v -> _)
//            }
//            .map(_.toMap)
//      }

    def latestMessagesInEra(
        keyBlockHash: ByteString
    )(implicit ev: A =:= Message): Map[Validator, Set[Message]] =
      data.get(keyBlockHash) match {
        case None => Map.empty[Validator, Set[Message]]
        case Some(lms) =>
          lms.mapValues {
            case Empty       => Set.empty[Message]
            case Honest(msg) => Set(msg.asInstanceOf[Message])
            case Equivocated(m1, m2) =>
              Set(m1.asInstanceOf[Message], m2.asInstanceOf[Message])
          }
      }
  }

  sealed trait ObservedValidatorBehavior[+A] extends Product with Serializable {
    def isEquivocated: Boolean
  }

  object ObservedValidatorBehavior {

    case class Honest[A](msg: A) extends ObservedValidatorBehavior[A] {
      override def isEquivocated: Boolean = false
    }

    case class Equivocated[A](m1: A, m2: A) extends ObservedValidatorBehavior[A] {
      override def isEquivocated: Boolean = true
    }

    case object Empty extends ObservedValidatorBehavior[Nothing] {
      override def isEquivocated: Boolean = false
    }

    implicit val functor: Functor[ObservedValidatorBehavior] =
      new Functor[ObservedValidatorBehavior] {
        def map[A, B](fa: ObservedValidatorBehavior[A])(f: A => B): ObservedValidatorBehavior[B] =
          fa match {
            case Honest(msg)         => Honest(f(msg))
            case Equivocated(m1, m2) => Equivocated(f(m1), f(m2))
            case Empty               => Empty
          }
      }
  }

  object EraObservedBehavior {
    type LocalDagView[A] = EraObservedBehavior[A] @@ LocalView
    type MessageJPast[A] = EraObservedBehavior[A] @@ MessageView

    def local(data: Map[ByteString, Map[Validator, Set[Message]]]): LocalDagView[Message] =
      apply(data).asInstanceOf[LocalDagView[Message]]

    def messageJPast(data: Map[ByteString, Map[Validator, Set[Message]]]): MessageJPast[Message] =
      apply(data).asInstanceOf[MessageJPast[Message]]

    private def apply(
        data: Map[ByteString, Map[Validator, Set[Message]]]
    ): EraObservedBehavior[Message] =
      new EraObservedBehavior(data.mapValues(_.map {
        case (v, lms) =>
          if (lms.isEmpty) v -> ObservedValidatorBehavior.Empty
          else if (lms.size == 1) v -> ObservedValidatorBehavior.Honest(lms.head)
          else v                    -> ObservedValidatorBehavior.Equivocated(lms.head, lms.drop(1).head)
      }))

    sealed trait LocalView

    sealed trait MessageView
  }
}

package io.casperlabs.casper.validation

import cats.{Applicative, Monad}
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.{state, Block, BlockSummary, Bond}
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.util.ProtoUtil.bonds
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, DagOperations, ProtoUtil}
import io.casperlabs.casper.validation.Errors._
import io.casperlabs.models.Message
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.crypto.Keys.{PublicKey, Signature}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Weight
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.{Success, Try}

object ValidationImpl {
  type Data        = Array[Byte]
  type BlockHeight = Long

  val DRIFT = 15000 // 15 seconds

  // TODO: put in chainspec https://casperlabs.atlassian.net/browse/NODE-911
  val MAX_TTL: Int          = 24 * 60 * 60 * 1000 // 1 day
  val MIN_TTL: Int          = 60 * 60 * 1000 // 1 hour
  val MAX_DEPENDENCIES: Int = 10

  def apply[F[_]](implicit ev: ValidationImpl[F]) = ev
}

class ValidationImpl[F[_]: MonadThrowable: FunctorRaise[?[_], InvalidBlock]: Log: Time: Metrics]
    extends Validation[F] {
  import ValidationImpl.DRIFT
  import ValidationImpl.MAX_TTL
  import ValidationImpl.MIN_TTL
  import ValidationImpl.MAX_DEPENDENCIES
  import io.casperlabs.models.BlockImplicits._

  type Data        = Array[Byte]
  type BlockHeight = Long

  def ignore(blockHash: ByteString, reason: String): F[Unit] =
    Log[F].warn(
      s"Ignoring ${PrettyPrinter.buildString(blockHash) -> "block"} because ${reason -> "reason" -> null}"
    )

  def raise(status: InvalidBlock) =
    FunctorRaise[F, InvalidBlock].raise[Unit](status)

  def reject(blockHash: ByteString, status: InvalidBlock, reason: String): F[Unit] =
    ignore(blockHash, reason) *> raise(status)

  def reject(block: Block, status: InvalidBlock, reason: String): F[Unit] =
    reject(block.blockHash, status, reason)

  def reject(summary: BlockSummary, status: InvalidBlock, reason: String): F[Unit] =
    reject(summary.blockHash, status, reason)

  private def checkDroppable(checks: F[Boolean]*): F[Unit] =
    checks.toList.sequence
      .map(_.forall(identity))
      .ifM(
        ().pure[F],
        MonadThrowable[F]
          .raiseError[Unit](DropErrorWrapper(InvalidUnslashableBlock))
      )

  def signatureVerifiers(sigAlgorithm: String): Option[(Data, Signature, PublicKey) => Boolean] =
    sigAlgorithm match {
      case SignatureAlgorithm(sa) => Some((data, sig, pub) => sa.verify(data, sig, pub))
      case _                      => None
    }

  def signature(d: Data, sig: consensus.Signature, key: PublicKey): Boolean =
    signatureVerifiers(sig.sigAlgorithm).fold(false) { verify =>
      verify(d, Signature(sig.sig.toByteArray), key)
    }

  /** Validate just the BlockSummary, assuming we don't have the block yet, or all its dependencies.
    * We can check that all the fields are present, the signature is fine, etc.
    * We'll need the full body to validate the block properly but this preliminary check can prevent
    * obviously corrupt data from being downloaded. */
  def blockSummary(
      summary: BlockSummary,
      chainName: String
  )(implicit versions: CasperLabsProtocolVersions[F]): F[Unit] = {
    val treatAsGenesis = summary.isGenesisLike
    for {
      _ <- checkDroppable(
            formatOfFields(summary, treatAsGenesis),
            version(
              summary,
              CasperLabsProtocolVersions[F].versionAt(_)
            ),
            if (!treatAsGenesis) blockSignature(summary) else true.pure[F]
          )
      _ <- summaryHash(summary)
      _ <- chainIdentifier(summary, chainName)
      _ <- ballot(summary)
    } yield ()
  }

  /** Check the block without executing deploys. */
  def blockFull(
      block: Block,
      dag: DagRepresentation[F],
      chainName: String,
      maybeGenesis: Option[Block]
  )(
      implicit bs: BlockStorage[F],
      versions: CasperLabsProtocolVersions[F],
      compiler: Fs2Compiler[F]
  ): F[Unit] = {
    val summary = BlockSummary(block.blockHash, block.header, block.signature)
    for {
      _ <- checkDroppable(
            if (block.body.isEmpty)
              ignore(block.blockHash, s"block body is missing.").as(false)
            else true.pure[F],
            // Validate that the sender is a bonded validator.
            maybeGenesis.fold(summary.isGenesisLike.pure[F]) { _ =>
              blockSender(summary)
            }
          )
      _ <- blockSummary(summary, chainName)
      // Checks that need dependencies.
      _ <- missingBlocks(summary)
      _ <- timestamp(summary)
      _ <- blockRank(summary, dag)
      _ <- validatorPrevBlockHash(summary, dag)
      _ <- sequenceNumber(summary, dag)
      _ <- swimlane(summary, dag)
      // Checks that need the body.
      _ <- blockHash(block)
      _ <- deployCount(block)
      _ <- deployHashes(block)
      _ <- deploySignatures(block)
      _ <- deployHeaders(block, dag, chainName)
      _ <- deployUniqueness(block, dag)
    } yield ()
  }

  def blockSignature(b: BlockSummary): F[Boolean] =
    signatureVerifiers(b.getSignature.sigAlgorithm) map { verify =>
      Try(
        verify(
          b.blockHash.toByteArray,
          Signature(b.getSignature.sig.toByteArray),
          PublicKey(b.validatorPublicKey.toByteArray)
        )
      ) match {
        case Success(true) => true.pure[F]
        case _             => ignore(b.blockHash, "signature is invalid.").as(false)
      }
    } getOrElse {
      ignore(b.blockHash, s"signature algorithm '${b.getSignature.sigAlgorithm}' is unsupported.")
        .as(false)
    }

  def deploySignature(d: consensus.Deploy): F[Boolean] =
    if (d.approvals.isEmpty) {
      Log[F].warn(
        s"Deploy ${PrettyPrinter.buildString(d.deployHash) -> "deployHash"} has no signatures."
      ) *> false.pure[F]
    } else {
      d.approvals.toList
        .traverse { a =>
          signatureVerifiers(a.getSignature.sigAlgorithm)
            .map { verify =>
              Try {
                verify(
                  d.deployHash.toByteArray,
                  Signature(a.getSignature.sig.toByteArray),
                  PublicKey(a.approverPublicKey.toByteArray)
                )
              } match {
                case Success(true) =>
                  true.pure[F]
                case _ =>
                  Log[F]
                    .warn(
                      s"Signature of deploy ${PrettyPrinter.buildString(d.deployHash) -> "deployHash"} is invalid."
                    )
                    .as(false)
              }
            } getOrElse {
            Log[F]
              .warn(
                s"Signature algorithm ${a.getSignature.sigAlgorithm} of deploy ${PrettyPrinter
                  .buildString(d.deployHash) -> "deployHash"} is unsupported."
              )
              .as(false)
          }
        }
        .map(_.forall(identity))
    }

  private def validateTimeToLive(
      ttl: Int,
      deployHash: ByteString
  ): F[Option[Errors.DeployHeaderError]] =
    if (ttl < MIN_TTL)
      Errors.DeployHeaderError.timeToLiveTooShort(deployHash, ttl, MIN_TTL).logged[F].map(_.some)
    else if (ttl > MAX_TTL)
      Errors.DeployHeaderError.timeToLiveTooLong(deployHash, ttl, MAX_TTL).logged[F].map(_.some)
    else
      none[Errors.DeployHeaderError].pure[F]

  private def validateDependencies(
      dependencies: Seq[ByteString],
      deployHash: ByteString
  ): F[List[Errors.DeployHeaderError]] = {
    val numDependencies = dependencies.length
    val tooMany =
      if (numDependencies > MAX_DEPENDENCIES)
        Errors.DeployHeaderError
          .tooManyDependencies(deployHash, numDependencies, MAX_DEPENDENCIES)
          .logged[F]
          .map(_.some)
      else
        none[Errors.DeployHeaderError].pure[F]

    val invalid = dependencies.toList
      .filter(_.size != 32)
      .traverse(dep => Errors.DeployHeaderError.invalidDependency(deployHash, dep).logged[F])

    Applicative[F].map2(tooMany, invalid)(_.toList ::: _)
  }

  private def validateChainName(
      chainName: String,
      deployChainName: String,
      deployHash: ByteString
  ): F[Option[Errors.DeployHeaderError]] =
    if (deployChainName.nonEmpty && deployChainName != chainName)
      Errors.DeployHeaderError
        .invalidChainName(deployHash, deployChainName, chainName)
        .logged[F]
        .map(_.some)
    else
      none[Errors.DeployHeaderError].pure[F]

  def deployHeader(d: consensus.Deploy, chainName: String): F[List[Errors.DeployHeaderError]] =
    d.header match {
      case Some(header) =>
        Applicative[F].map3(
          validateTimeToLive(ProtoUtil.getTimeToLive(header, MAX_TTL), d.deployHash),
          validateDependencies(header.dependencies, d.deployHash),
          validateChainName(chainName, header.chainName, d.deployHash)
        ) {
          case (validTTL, validDependencies, validChainNames) =>
            validTTL.toList ::: validDependencies ::: validChainNames.toList
        }

      case None =>
        Errors.DeployHeaderError.MissingHeader(d.deployHash).logged[F].map(List(_))
    }

  def blockSender(block: BlockSummary)(implicit bs: BlockStorage[F]): F[Boolean] =
    for {
      weight <- ProtoUtil.weightFromSender[F](block.getHeader)
      result <- if (weight > 0) true.pure[F]
               else
                 ignore(
                   block.blockHash,
                   s"block creator ${PrettyPrinter.buildString(block.validatorPublicKey)} has 0 weight."
                 ).as(false)
    } yield result

  def formatOfFields(
      b: BlockSummary,
      treatAsGenesis: Boolean = false
  ): F[Boolean] = {
    def invalid(msg: String) =
      ignore(b.blockHash, msg).as(false)

    if (b.blockHash.isEmpty) {
      invalid(s"block hash is empty.")
    } else if (b.header.isEmpty) {
      invalid(s"block header is missing.")
    } else if (b.getSignature.sig.isEmpty && !treatAsGenesis) {
      invalid(s"block signature is empty.")
    } else if (!b.getSignature.sig.isEmpty && treatAsGenesis) {
      invalid(s"block signature is not empty on Genesis.")
    } else if (b.getSignature.sigAlgorithm.isEmpty && !treatAsGenesis) {
      invalid(s"block signature algorithm is not empty on Genesis.")
    } else if (!b.getSignature.sigAlgorithm.isEmpty && treatAsGenesis) {
      invalid(s"block signature algorithm is empty.")
    } else if (b.chainName.isEmpty) {
      invalid(s"block chain identifier is empty.")
    } else if (b.state.postStateHash.isEmpty) {
      invalid(s"block post state hash is empty.")
    } else if (b.bodyHash.isEmpty) {
      invalid(s"block new code hash is empty.")
    } else {
      true.pure[F]
    }
  }

  // Validates whether block was built using correct protocol version.
  def version(
      b: BlockSummary,
      m: BlockHeight => F[state.ProtocolVersion]
  ): F[Boolean] = {

    val blockVersion = b.getHeader.getProtocolVersion
    val blockHeight  = b.getHeader.rank
    m(blockHeight).flatMap { version =>
      if (blockVersion == version) {
        true.pure[F]
      } else {
        ignore(
          b.blockHash,
          s"Received block version $blockVersion, expected version $version."
        ).as(false)
      }
    }
  }

  /**
    * Works with either efficient justifications or full explicit justifications
    */
  def missingBlocks(
      block: BlockSummary
  )(implicit bs: BlockStorage[F]): F[Unit] =
    for {
      parentsPresent <- block.parentHashes.toList
                         .forallM(p => BlockStorage[F].contains(p))
      justificationsPresent <- block.justifications.toList
                                .forallM(j => BlockStorage[F].contains(j.latestBlockHash))
      _ <- reject(block, MissingBlocks, "parents or justifications are missing from storage")
            .whenA(!parentsPresent || !justificationsPresent)
    } yield ()

  // This is not a slashable offence
  def timestamp(
      b: BlockSummary
  )(implicit bs: BlockStorage[F]): F[Unit] =
    for {
      currentTime  <- Time[F].currentMillis
      timestamp    = b.timestamp
      beforeFuture = currentTime + ValidationImpl.DRIFT >= timestamp
      dependencies = b.parentHashes ++ b.getHeader.justifications.map(_.latestBlockHash)
      latestDependencyTimestamp <- dependencies.distinct.toList.foldM(0L) {
                                    case (latestTimestamp, blockHash) =>
                                      ProtoUtil
                                        .unsafeGetBlockSummary[F](blockHash)
                                        .map(block => {
                                          val timestamp =
                                            block.header.fold(latestTimestamp)(_.timestamp)
                                          math.max(latestTimestamp, timestamp)
                                        })
                                  }
      afterLatestDependency = timestamp >= latestDependencyTimestamp
      _ <- if (beforeFuture && afterLatestDependency) {
            Applicative[F].unit
          } else {
            reject(
              b,
              InvalidUnslashableBlock,
              s"block timestamp $timestamp is not between latest justification block time and current time."
            )
          }
    } yield ()

  /* If we receive block from future then we may fail to propose new block on top of it because of Validation.timestamp */
  def preTimestamp(
      b: Block
  ): F[Option[FiniteDuration]] =
    for {
      currentMillis <- Time[F].currentMillis
      delay <- b.timestamp - currentMillis match {
                case n if n <= 0     => none[FiniteDuration].pure[F]
                case n if n <= DRIFT =>
                  // Sleep for a little bit more time to ensure we won't propose block on top of block from future
                  FiniteDuration(n + 500, MILLISECONDS).some.pure[F]
                case _ =>
                  RaiseValidationError[F].raise[Option[FiniteDuration]](InvalidUnslashableBlock)
              }
    } yield delay

  // Block rank is 1 plus the maximum of the rank of its justifications.
  def blockRank(
      b: BlockSummary,
      dag: DagRepresentation[F]
  ): F[Unit] =
    for {
      justificationMsgs <- (b.parents ++ b.justifications.map(_.latestBlockHash)).toSet.toList
                            .traverse(dag.lookupUnsafe(_))
      calculatedRank = ProtoUtil.nextRank(justificationMsgs)
      actuallyRank   = b.rank
      result         = calculatedRank == actuallyRank
      _ <- if (result) {
            Applicative[F].unit
          } else {
            val msg =
              if (justificationMsgs.isEmpty)
                s"block number $actuallyRank is not zero, but block has no justifications."
              else
                s"block number $actuallyRank is not the maximum block number of justifications plus 1, i.e. $calculatedRank."
            reject(b, InvalidBlockNumber, msg)
          }
    } yield ()

  // Validates that a message that is supposed to be a ballot adheres to ballot's specification.
  private def ballot(b: BlockSummary): F[Unit] =
    FunctorRaise[F, InvalidBlock]
      .raise[Unit](InvalidTargetHash)
      .whenA(b.getHeader.messageType.isBallot && b.getHeader.parentHashes.size != 1)

  // Check whether message merges its creator swimlane.
  // A block cannot have more than one latest message in its j-past-cone from its creator.
  // i.e. an equivocator cannot cite multiple of its latest messages.
  def swimlane(b: BlockSummary, dag: DagRepresentation[F]): F[Unit] =
    for {
      equivocators <- dag.getEquivocators
      message      <- MonadThrowable[F].fromTry(Message.fromBlockSummary(b))
      _ <- Monad[F].whenA(equivocators.contains(message.validatorId)) {
            for {
              equivocations       <- dag.getEquivocations.map(_(message.validatorId))
              equivocationsHashes = equivocations.map(_.messageHash)
              minRank = EquivocationDetector
                .findMinBaseRank(Map(message.validatorId -> equivocations))
                .getOrElse(0L) // We know it has to be defined by this point.
              seenEquivocations <- DagOperations
                                    .swimlaneV[F](message.validatorId, message, dag)
                                    .foldWhileLeft(Set.empty[BlockHash]) {
                                      case (seenEquivocations, message) =>
                                        if (message.rank <= minRank) {
                                          Right(seenEquivocations)
                                        } else {
                                          if (equivocationsHashes.contains(message.messageHash)) {
                                            if (seenEquivocations.nonEmpty) {
                                              Right(seenEquivocations + message.messageHash)
                                            } else Left(Set(message.messageHash))
                                          } else Left(seenEquivocations)
                                        }
                                    }
              _ <- Monad[F].whenA(seenEquivocations.size > 1) {
                    val msg =
                      s"${PrettyPrinter.buildString(message.messageHash)} cites multiple latest message by its creator ${PrettyPrinter
                        .buildString(message.validatorId)}: ${seenEquivocations
                        .map(PrettyPrinter.buildString)
                        .mkString("[", ",", "]")}"
                    reject(b, SwimlaneMerged, msg)
                  }
            } yield ()
          }
    } yield ()

  /**
    * Works with either efficient justifications or full explicit justifications.
    * Specifically, with efficient justifications, if a block B doesn't update its
    * creator justification, this check will fail as expected. The exception is when
    * B's creator justification is the genesis block.
    */
  def sequenceNumber(
      b: BlockSummary,
      dag: DagRepresentation[F]
  ): F[Unit] =
    if (b.isGenesisLike)
      FunctorRaise[F, InvalidBlock]
        .raise[Unit](InvalidSequenceNumber)
        .whenA(b.validatorBlockSeqNum != 0)
    else
      for {
        creatorJustificationSeqNumber <- ProtoUtil.nextValidatorBlockSeqNum(
                                          dag,
                                          b.getHeader.validatorPrevBlockHash
                                        )
        number = b.validatorBlockSeqNum
        ok     = creatorJustificationSeqNumber == number
        _ <- if (ok) {
              Applicative[F].unit
            } else {
              reject(
                b,
                InvalidSequenceNumber,
                s"seq number $number is not one more than creator justification number $creatorJustificationSeqNumber."
              )
            }
      } yield ()

  /** Validate that the j-past-cone of the block cites the previous block hash,
    * except if this is the first block the validator created.
    */
  def validatorPrevBlockHash(
      b: BlockSummary,
      dag: DagRepresentation[F]
  ): F[Unit] = {
    val prevBlockHash = b.getHeader.validatorPrevBlockHash
    val validatorId   = b.getHeader.validatorPublicKey
    if (prevBlockHash.isEmpty) {
      ().pure[F]
    } else {
      def rejectWith(msg: String) =
        reject(b, InvalidPrevBlockHash, msg)

      dag.lookup(prevBlockHash).flatMap {
        case None =>
          rejectWith(
            s"DagStorage is missing previous block hash ${PrettyPrinter.buildString(prevBlockHash)}"
          )
        case Some(meta) if meta.validatorId != validatorId =>
          rejectWith(
            s"Previous block hash ${PrettyPrinter.buildString(prevBlockHash)} was not created by validator ${PrettyPrinter
              .buildString(validatorId)}"
          )
        case Some(meta) =>
          MonadThrowable[F].fromTry(Message.fromBlockSummary(b)) flatMap { blockMsg =>
            DagOperations
              .toposortJDagDesc(dag, List(blockMsg))
              .find { j =>
                j.validatorId == validatorId && j.messageHash != b.blockHash || j.rank < meta.rank
              }
              .flatMap {
                case Some(msg) if msg.messageHash == prevBlockHash =>
                  ().pure[F]
                case Some(msg) if msg.validatorId == validatorId =>
                  rejectWith(
                    s"The previous block hash from this validator in the j-past-cone is ${PrettyPrinter
                      .buildString(msg.messageHash)}, not the expected ${PrettyPrinter.buildString(prevBlockHash)}"
                  )
                case _ =>
                  rejectWith(
                    s"Could not find any previous block hash from the validator in the j-past-cone."
                  )
              }
          }
      }
    }
  }

  // Agnostic of justifications
  def chainIdentifier(
      b: BlockSummary,
      chainName: String
  ): F[Unit] =
    if (b.chainName == chainName) {
      Applicative[F].unit
    } else {
      reject(
        b,
        InvalidChainName,
        s"got chain identifier ${b.chainName} while $chainName was expected."
      )
    }

  def deployHash(d: consensus.Deploy): F[Boolean] = {
    val bodyHash   = ProtoUtil.protoHash(d.getBody)
    val deployHash = ProtoUtil.protoHash(d.getHeader)
    val ok         = bodyHash == d.getHeader.bodyHash && deployHash == d.deployHash

    def logDiff = {
      // Print the full length, maybe the client has configured their hasher to output 64 bytes.
      def b16(bytes: ByteString) = Base16.encode(bytes.toByteArray)
      for {
        _ <- Log[F]
              .warn(
                s"Invalid deploy body hash; got ${b16(d.getHeader.bodyHash) -> "bodyHash"}, expected ${b16(bodyHash) -> "expectedBodyHash"}"
              )
        _ <- Log[F]
              .warn(
                s"Invalid deploy hash; got ${b16(d.deployHash) -> "deployHash"}, expected ${b16(deployHash) -> "expectedDeployHash"}"
              )
      } yield ()
    }

    logDiff.whenA(!ok).as(ok)
  }

  def blockHash(
      b: Block
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val bodyHashComputed  = ProtoUtil.protoHash(b.getBody)

    if (b.blockHash == blockHashComputed &&
        b.bodyHash == bodyHashComputed) {
      Applicative[F].unit
    } else {
      def show(hash: ByteString) = PrettyPrinter.buildString(hash)
      for {
        _ <- Log[F]
              .warn(
                s"Expected block hash ${show(blockHashComputed) -> "expectedBlockHash"}; got ${show(b.blockHash) -> "blockHash"}"
              )
              .whenA(b.blockHash != blockHashComputed)
        _ <- Log[F]
              .warn(
                s"Expected body hash ${show(bodyHashComputed) -> "expectedBodyHash"}; got ${show(b.bodyHash) -> "bodyHash"}"
              )
              .whenA(b.bodyHash != bodyHashComputed)
        _ <- reject(b.blockHash, InvalidBlockHash, "block hash does not match to computed value.")
      } yield ()
    }
  }

  def summaryHash(
      b: BlockSummary
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val ok                = b.blockHash == blockHashComputed
    reject(b, InvalidBlockHash, s"invalid block hash").whenA(!ok)
  }

  def deployCount(
      b: Block
  ): F[Unit] =
    if (b.deployCount == b.getBody.deploys.length) {
      Applicative[F].unit
    } else {
      reject(b, InvalidDeployCount, s"block deploy count does not match to the amount of deploys.")
    }

  def deployHeaders(b: Block, dag: DagRepresentation[F], chainName: String)(
      implicit blockStorage: BlockStorage[F]
  ): F[Unit] = {
    val deploys: List[consensus.Deploy] = b.getBody.deploys.flatMap(_.deploy).toList
    val parents: Set[BlockHash] =
      b.header.toSet.flatMap((h: consensus.Block.Header) => h.parentHashes)
    val timestamp       = b.getHeader.timestamp
    val isFromPast      = DeployFilters.timestampBefore(timestamp)
    val isNotExpired    = DeployFilters.notExpired(timestamp)
    val dependenciesMet = DeployFilters.dependenciesMet[F](dag, parents)

    def singleDeployValidation(d: consensus.Deploy): F[Unit] =
      for {
        staticErrors           <- deployHeader(d, chainName)
        _                      <- raiseHeaderErrors(staticErrors).whenA(staticErrors.nonEmpty)
        header                 = d.getHeader
        isFromFuture           = !isFromPast(header)
        _                      <- raiseFutureDeploy(d.deployHash, header).whenA(isFromFuture)
        isExpired              = !isNotExpired(header)
        _                      <- raiseExpiredDeploy(d.deployHash, header).whenA(isExpired)
        hasMissingDependencies <- dependenciesMet(d).map(!_)
        _                      <- raiseDeployDependencyNotMet(d).whenA(hasMissingDependencies)
      } yield ()

    def raiseHeaderErrors(errors: List[Errors.DeployHeaderError]): F[Unit] =
      reject(b, InvalidDeployHeader, errors.map(_.errorMessage).mkString(". "))

    def raiseFutureDeploy(deployHash: DeployHash, header: consensus.Deploy.Header): F[Unit] = {
      val hash = PrettyPrinter.buildString(deployHash)
      reject(
        b,
        DeployFromFuture,
        s"block timestamp $timestamp is earlier than timestamp of deploy $hash, ${header.timestamp}"
      )
    }

    def raiseExpiredDeploy(deployHash: DeployHash, header: consensus.Deploy.Header): F[Unit] = {
      val hash           = PrettyPrinter.buildString(deployHash)
      val ttl            = ProtoUtil.getTimeToLive(header, MAX_TTL)
      val expirationTime = header.timestamp + ttl
      reject(
        b,
        DeployExpired,
        s"block timestamp $timestamp is later than expiration time of deploy $hash, $expirationTime"
      )
    }

    def raiseDeployDependencyNotMet(deploy: consensus.Deploy): F[Unit] =
      reject(
        b,
        DeployDependencyNotMet,
        s"${PrettyPrinter.buildString(deploy)} did not have all dependencies met."
      )

    deploys.traverse(singleDeployValidation).as(())
  }

  def deployHashes(
      b: Block
  ): F[Unit] =
    b.getBody.deploys.toList.findM(d => deployHash(d.getDeploy).map(!_)).flatMap {
      case None =>
        Applicative[F].unit
      case Some(d) =>
        reject(b, InvalidDeployHash, s"${PrettyPrinter.buildString(d.getDeploy)} has invalid hash.")
    }

  def deploySignatures(
      b: Block
  ): F[Unit] =
    b.getBody.deploys.toList
      .findM(d => deploySignature(d.getDeploy).map(!_))
      .flatMap {
        case None =>
          Applicative[F].unit
        case Some(d) =>
          reject(
            b,
            InvalidDeploySignature,
            s"${PrettyPrinter.buildString(d.getDeploy)} has invalid signature."
          )
      }
      .whenA(!b.isGenesisLike)

  /**
    * Checks that the parents of `b` were chosen correctly according to the
    * forkchoice rule. This is done by using the justifications of `b` as the
    * set of latest messages, so the justifications must be fully explicit.
    * For multi-parent blocks this requires doing commutativity checking, so
    * the combined effect of all parents except the first (i.e. the effect
    * which would need to be applied to the first parent's post-state to
    * obtain the pre-state of `b`) is given as the return value in order to
    * avoid repeating work downstream.
    */
  def parents(
      b: Block,
      genesisHash: BlockHash,
      dag: DagRepresentation[F]
  )(
      implicit bs: BlockStorage[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]] = {
    def printHashes(hashes: Iterable[ByteString]) =
      hashes.map(PrettyPrinter.buildString).mkString("[", ", ", "]")

    val latestMessagesHashes = ProtoUtil
      .getJustificationMsgHashes(b.getHeader.justifications)

    for {
      equivocators <- EquivocationDetector.detectVisibleFromJustifications(
                       dag,
                       latestMessagesHashes
                     )
      tipHashes            <- Estimator.tips[F](dag, genesisHash, latestMessagesHashes, equivocators)
      _                    <- Log[F].debug(s"Estimated tips are ${printHashes(tipHashes) -> "tips"}")
      tips                 <- tipHashes.toVector.traverse(ProtoUtil.unsafeGetBlock[F])
      merged               <- ExecEngineUtil.merge[F](tips, dag)
      computedParentHashes = merged.parents.map(_.blockHash)
      parentHashes         = ProtoUtil.parentHashes(b)
      _ <- if (parentHashes.isEmpty)
            raise(InvalidParents)
          else if (parentHashes == computedParentHashes)
            Applicative[F].unit
          else {
            val parentsString =
              parentHashes.map(PrettyPrinter.buildString).mkString(",")
            val estimateString =
              computedParentHashes.map(PrettyPrinter.buildString).mkString(",")
            val justificationString = latestMessagesHashes.values
              .map(hashes => hashes.map(PrettyPrinter.buildString).mkString("[", ",", "]"))
              .mkString(",")
            reject(
              b,
              InvalidParents,
              s"block parents $parentsString did not match estimate $estimateString based on justification $justificationString."
            )
          }
    } yield merged
  }

  // Validates whether received block is valid (according to that nodes logic):
  // 1) Validates whether pre state hashes match
  // 2) Runs deploys from the block
  // 3) Validates whether post state hashes match
  // 4) Validates whether bonded validators, as at the end of executing the block, match.
  def transactions(
      block: Block,
      preStateHash: StateHash,
      effects: Seq[ipc.TransformEntry]
  )(implicit ee: ExecutionEngineService[F]): F[Unit] = {
    val blockPreState  = ProtoUtil.preStateHash(block)
    val blockPostState = ProtoUtil.postStateHash(block)
    if (preStateHash == blockPreState) {
      for {
        possibleCommitResult <- ExecutionEngineService[F].commit(
                                 preStateHash,
                                 effects,
                                 block.getHeader.getProtocolVersion
                               )
        //TODO: distinguish "internal errors" and "user errors"
        _ <- possibleCommitResult match {
              case Left(ex) =>
                Log[F].error(
                  s"Could not commit effects of ${PrettyPrinter.buildString(block.blockHash) -> "block"}: $ex"
                ) *>
                  raise(InvalidTransaction)
              case Right(commitResult) =>
                for {
                  _ <- reject(block, InvalidPostStateHash, "invalid post state hash")
                        .whenA(commitResult.postStateHash != blockPostState)
                  _ <- bondsCache(block, commitResult.bondedValidators)
                } yield ()
            }
      } yield ()
    } else {
      raise(InvalidPreStateHash)
    }
  }

  /**
    * If block contains an invalid justification block B and the creator of B is still bonded,
    * return a RejectableBlock. Otherwise return an IncludeableBlock.
    */
  def neglectedInvalidBlock(
      block: Block,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit] = {
    val invalidJustifications = block.justifications.filter(
      justification => invalidBlockTracker.contains(justification.latestBlockHash)
    )
    val neglectedInvalidJustification = invalidJustifications.exists { justification =>
      val slashedValidatorBond =
        bonds(block).find(_.validatorPublicKey == justification.validatorPublicKey)
      slashedValidatorBond match {
        case Some(bond) => Weight(bond.stake) > 0
        case None       => false
      }
    }
    if (neglectedInvalidJustification) {
      reject(block, NeglectedInvalidBlock, "Neglected invalid justification.")
    } else {
      Applicative[F].unit
    }
  }

  def bondsCache(
      b: Block,
      computedBonds: Seq[Bond]
  ): F[Unit] = {
    val bonds = ProtoUtil.bonds(b)
    ProtoUtil.postStateHash(b) match {
      case globalStateRootHash if !globalStateRootHash.isEmpty =>
        if (bonds.toSet == computedBonds.toSet) {
          Applicative[F].unit
        } else {
          reject(
            b,
            InvalidBondsCache,
            "Bonds in proof of stake contract do not match block's bond cache."
          )
        }
      case _ =>
        reject(
          b,
          InvalidBondsCache,
          s"Block ${PrettyPrinter.buildString(b)} is missing a post state hash."
        )
    }
  }

  /** Check that none of the deploys in the block have been included in another block already
    * which was in the P-past cone of the block itself.
    */
  def deployUniqueness(
      block: Block,
      dag: DagRepresentation[F]
  )(implicit bs: BlockStorage[F]): F[Unit] = {
    val deploys        = block.getBody.deploys.map(_.getDeploy).toList
    val maybeDuplicate = deploys.groupBy(_.deployHash).find(_._2.size > 1).map(_._2.head)
    maybeDuplicate match {
      case Some(duplicate) =>
        reject(
          block,
          InvalidRepeatDeploy,
          s"block contains duplicate ${PrettyPrinter.buildString(duplicate)}"
        )

      case None =>
        for {
          deployToBlocksMap <- deploys
                                .traverse { deploy =>
                                  bs.findBlockHashesWithDeployHash(deploy.deployHash).map {
                                    blockHashes =>
                                      deploy -> blockHashes.filterNot(_ == block.blockHash)
                                  }
                                }
                                .map(_.toMap)

          blockHashes = deployToBlocksMap.values.flatten.toSet

          duplicateBlockHashes <- DagOperations.collectWhereDescendantPathExists(
                                   dag,
                                   blockHashes,
                                   Set(block.blockHash)
                                 )

          _ <- if (duplicateBlockHashes.isEmpty) ().pure[F]
              else {
                val exampleBlockHash = duplicateBlockHashes.head
                val exampleDeploy = deployToBlocksMap.collectFirst {
                  case (deploy, blockHashes) if blockHashes.contains(exampleBlockHash) =>
                    deploy
                }.get
                reject(
                  block,
                  InvalidRepeatDeploy,
                  s"block contains a duplicate ${PrettyPrinter.buildString(exampleDeploy)} already present in ${PrettyPrinter
                    .buildString(exampleBlockHash)}"
                )
              }

        } yield ()
    }
  }
}

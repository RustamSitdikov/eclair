/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.ActorSystem
import fr.acinq.bitcoin.{BinaryData, OutPoint, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, JsonRPCError}
import fr.acinq.eclair.transactions.Transactions
import grizzled.slf4j.Logging
import org.json4s.JsonAST._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 06/07/2017.
  */
class BitcoinCoreWallet(rpcClient: BitcoinJsonRPCClient)(implicit system: ActorSystem, ec: ExecutionContext) extends EclairWallet with Logging {

  import BitcoinCoreWallet._


  def fundTransaction(hex: String, lockUnspents: Boolean): Future[FundTransactionResponse] = {
    rpcClient.invoke("fundrawtransaction", hex, BitcoinCoreWallet.Options(lockUnspents)).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changepos) = json \ "changepos"
      val JDouble(fee) = json \ "fee"
      FundTransactionResponse(Transaction.read(hex), changepos.intValue(), (fee * 10e8).toLong)
    })
  }

  def fundTransaction(tx: Transaction, lockUnspents: Boolean): Future[FundTransactionResponse] = fundTransaction(Transaction.write(tx).toString(), lockUnspents)

  def signTransaction(hex: String): Future[SignTransactionResponse] =
    rpcClient.invoke("signrawtransaction", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      SignTransactionResponse(Transaction.read(hex), complete)
    })

  def signTransaction(tx: Transaction): Future[SignTransactionResponse] = signTransaction(Transaction.write(tx).toString())

  def getTransaction(txid: BinaryData): Future[Transaction] = rpcClient.invoke("getrawtransaction", txid.toString()) collect { case JString(hex) => Transaction.read(hex) }

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] = publishTransaction(Transaction.write(tx).toString())

  def publishTransaction(hex: String)(implicit ec: ExecutionContext): Future[String] = rpcClient.invoke("sendrawtransaction", hex) collect { case JString(txid) => txid }

  def unlockOutpoint(outPoints: List[OutPoint])(implicit ec: ExecutionContext): Future[Boolean] = rpcClient.invoke("lockunspent", true, outPoints.map(outPoint => Utxo(outPoint.txid.toString, outPoint.index))) collect { case JBool(result) => result }


  override def getBalance: Future[Satoshi] = rpcClient.invoke("getbalance") collect { case JDouble(balance) => Satoshi((balance * 10e8).toLong) }

  override def getFinalAddress: Future[String] = for {
    JString(address) <- rpcClient.invoke("getnewaddress")
  } yield address

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] = {
    // partial funding tx
    val partialFundingTx = Transaction(
      version = 2,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0)
    for {
      // we ask bitcoin core to add inputs to the funding tx, and use the specified change address
      FundTransactionResponse(unsignedFundingTx, changepos, fee) <- fundTransaction(partialFundingTx, lockUnspents = true)
      // now let's sign the funding tx
      SignTransactionResponse(fundingTx, _) <- signTransaction(unsignedFundingTx)
      // there will probably be a change output, so we need to find which output is ours
      outputIndex = Transactions.findPubKeyScriptIndex(fundingTx, pubkeyScript)
      _ = logger.debug(s"created funding txid=${fundingTx.txid} outputIndex=$outputIndex fee=$fee")
    } yield MakeFundingTxResponse(fundingTx, outputIndex)
  }

  override def commit(tx: Transaction): Future[Boolean] = publishTransaction(tx)
    .map(_ => true) // if bitcoind says OK, then we consider the tx successfully published
    .recoverWith { case JsonRPCError(e) =>
    logger.warn(s"txid=${tx.txid} error=$e")
    getTransaction(tx.txid).map(_ => true).recover { case _ => false } // if we get a parseable error from bitcoind AND the tx is NOT in the mempool/blockchain, then we consider that the tx was not published
  }
    .recover { case _ => true } // in all other cases we consider that the tx has been published

  override def rollback(tx: Transaction): Future[Boolean] = unlockOutpoint(tx.txIn.map(_.outPoint).toList) // we unlock all utxos used by the tx

}

object BitcoinCoreWallet {

  // @formatter:off
  case class Options(lockUnspents: Boolean)
  case class Utxo(txid: String, vout: Long)
  case class FundTransactionResponse(tx: Transaction, changepos: Int, feeSatoshis: Long)
  case class SignTransactionResponse(tx: Transaction, complete: Boolean)
  // @formatter:on


}
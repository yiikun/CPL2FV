/** *************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  *          http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  * *************************************************************************************
  */

// See LICENSE.SiFive for license details.

package coupledL2

import chisel3._
import chisel3.util._
import utility.{FastArbiter, Pipeline}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.util._
import chipsalliance.rocketchip.config.Parameters
import scala.math.max
import coupledL2.prefetch._

trait HasCoupledL2Parameters {
  val p: Parameters
  val cacheParams = p(L2ParamKey)

  val blocks = cacheParams.sets * cacheParams.ways
  val blockBytes = cacheParams.blockBytes
  val beatBytes = cacheParams.channelBytes.d.get
  val beatSize = blockBytes / beatBytes

  val wayBits = log2Ceil(cacheParams.ways)
  val setBits = log2Ceil(cacheParams.sets)
  val offsetBits = log2Ceil(blockBytes)
  val beatBits = offsetBits - log2Ceil(beatBytes)
  val stateBits = MetaData.stateBits
  val aliasBitsOpt = if(cacheParams.clientCaches.isEmpty) None
                  else cacheParams.clientCaches.head.aliasBitsOpt
  val pageOffsetBits = log2Ceil(cacheParams.pageBytes)

  val mshrsAll = 16
  val idsAll = 128 // TODO: parameterize this?
  val mshrBits = log2Up(idsAll)

  val bufBlocks = 8 // hold data that flows in MainPipe
  val bufIdxBits = log2Up(bufBlocks)

  // 1 cycle for sram read, and latch for another cycle
  val sramLatency = 2

  val releaseBufWPorts = 3 // sinkC and mainpipe s5, s6
  
  // Prefetch
  val prefetchOpt = cacheParams.prefetch
  val hasPrefetchBit = prefetchOpt.nonEmpty && prefetchOpt.get.hasPrefetchBit

  lazy val edgeIn = p(EdgeInKey)
  lazy val edgeOut = p(EdgeOutKey)
  lazy val bankBits = p(BankBitsKey)

  lazy val clientBits = edgeIn.client.clients.count(_.supports.probe)
  lazy val sourceIdBits = edgeIn.bundle.sourceBits
  lazy val msgSizeBits = edgeIn.bundle.sizeBits
  lazy val sourceIdAll = 1 << sourceIdBits
  // id of 0XXXX refers to mshrid
  // id of 1XXXX refers to reqs that do not enter mshr
  // require(isPow2(idsAll))
  // require(idsAll >= mshrsAll * 2)
  // require(idsAll >= sourceIdAll * 2)

  // width params with bank idx (used in prefetcher / ctrl unit)
  lazy val fullAddressBits = edgeOut.bundle.addressBits
  lazy val fullTagBits = fullAddressBits - setBits - offsetBits
  // width params without bank idx (used in slice)
  // TODO: consider bankbits
  lazy val addressBits = fullAddressBits - bankBits
  lazy val tagBits = fullTagBits - bankBits

  lazy val outerSinkBits = edgeOut.bundle.sinkBits

  def getClientBitOH(sourceId: UInt): UInt = {
    if (clientBits == 0) {
      0.U
    } else {
      Cat(
        edgeIn.client.clients
          .filter(_.supports.probe)
          .map(c => {
            c.sourceId.contains(sourceId).asInstanceOf[Bool]
          })
          .reverse
      )
    }
  }

  def getSourceId(client: UInt): UInt = {
    if (clientBits == 0) {
      0.U
    } else {
      Mux1H(
        client,
        edgeIn.client.clients
          .filter(_.supports.probe)
          .map(c => c.sourceId.start.U)
      )
    }
  }

  def parseFullAddress(x: UInt): (UInt, UInt, UInt) = {
    val offset = x // TODO: check address mapping
    val set = offset >> offsetBits
    val tag = set >> setBits
    (tag(fullTagBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0))
  }

  def parseAddress(x: UInt): (UInt, UInt, UInt) = {
    val offset = x
    val set = offset >> (offsetBits + bankBits)
    val tag = set >> setBits
    (tag(tagBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0))
  }

  def getPPN(x: UInt): UInt = {
    x(x.getWidth - 1, pageOffsetBits)
  }

  def fastArb[T <: Bundle](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
    val arb = Module(new FastArbiter[T](chiselTypeOf(out.bits), in.size))
    if (name.nonEmpty) { arb.suggestName(s"${name.get}_arb") }
    for ((a, req) <- arb.io.in.zip(in)) { a <> req }
    out <> arb.io.out
  }

  def odOpGen(r: UInt) = {
    val grantOp = GrantData
    val opSeq = Seq(AccessAck, AccessAck, AccessAckData, AccessAckData, AccessAckData, HintAck, grantOp, Grant)
    val opToA = VecInit(opSeq)(r)
    opToA
  }
}

trait DontCareInnerLogic { this: Module =>
  override def IO[T <: Data](iodef: T): T = {
    val p = chisel3.experimental.IO.apply(iodef)
    p <> DontCare
    p
  }
}

class CoupledL2(implicit p: Parameters) extends LazyModule with HasCoupledL2Parameters {

  val xfer = TransferSizes(blockBytes, blockBytes)
  val atom = TransferSizes(1, cacheParams.channelBytes.d.get)
  val access = TransferSizes(1, blockBytes)

  val clientPortParams = (m: TLMasterPortParameters) => TLMasterPortParameters.v2(
    Seq(
      TLMasterParameters.v2(
        name = cacheParams.name,
        supports = TLSlaveToMasterTransferSizes(
          probe = xfer
        ),
        sourceId = IdRange(0, idsAll)
      )
    ),
    channelBytes = cacheParams.channelBytes,
    minLatency = 1,
    echoFields = cacheParams.echoField,
    requestFields = cacheParams.reqField,
    responseKeys = cacheParams.respKey
  )

  val managerPortParams = (m: TLSlavePortParameters) => TLSlavePortParameters.v1(
    m.managers.map { m =>
      m.v2copy(
        regionType = if (m.regionType >= RegionType.UNCACHED) RegionType.CACHED else m.regionType,
        supports = TLMasterToSlaveTransferSizes(
          acquireB = xfer,
          acquireT = if (m.supportsAcquireT) xfer else TransferSizes.none,
          arithmetic = if (m.supportsAcquireT) atom else TransferSizes.none,
          logical = if (m.supportsAcquireT) atom else TransferSizes.none,
          get = access,
          putFull = if (m.supportsAcquireT) access else TransferSizes.none,
          putPartial = if (m.supportsAcquireT) access else TransferSizes.none,
          hint = access
        ),
        fifoId = None
      )
    },
    beatBytes = 32,
    minLatency = 2,
    responseFields = cacheParams.respField,
    requestKeys = cacheParams.reqKey,
    endSinkId = idsAll
  )

  val node = TLAdapterNode(
    clientFn = clientPortParams,
    managerFn = managerPortParams
  )

  val pf_recv_node: Option[BundleBridgeSink[PrefetchRecv]] = prefetchOpt match {
    case Some(_: PrefetchReceiverParams) =>
      Some(BundleBridgeSink(Some(() => new PrefetchRecv)))
    case _ => None
  }

  lazy val module = new LazyModuleImp(this) {
    val banks = node.in.size
    val bankBits = if (banks == 1) 0 else log2Up(banks)

    // Display info
    val sizeBytes = cacheParams.toCacheParams.capacity.toDouble
    def sizeBytesToStr(sizeBytes: Double): String = sizeBytes match {
      case _ if sizeBytes >= 1024 * 1024 => (sizeBytes / 1024 / 1024) + "MB"
      case _ if sizeBytes >= 1024        => (sizeBytes / 1024) + "KB"
      case _                            => "B"
    }
    val sizeStr = sizeBytesToStr(sizeBytes)
    val prefetch = "prefetch: " + cacheParams.prefetch
    println(s"====== Inclusive ${cacheParams.name} ($sizeStr * $banks-bank) $prefetch ======")
    println(s"bankBits: ${bankBits}")
    println(s"sets:${cacheParams.sets} ways:${cacheParams.ways} blockBytes:${cacheParams.blockBytes}")
    def print_bundle_fields(fs: Seq[BundleFieldBase], prefix: String) = {
      if(fs.nonEmpty){
        println(fs.map{f => s"$prefix/${f.key.name}: (${f.data.getWidth}-bit)"}.mkString("\n"))
      }
    }
    print_bundle_fields(node.in.head._2.bundle.requestFields, "usr")
    print_bundle_fields(node.in.head._2.bundle.echoFields, "echo")

    node.edges.in.headOption.foreach { n =>
      n.client.clients.zipWithIndex.foreach {
        case (c, i) =>
          println(s"\t${i} <= ${c.name}")
      }
    }

    // connection between prefetcher and the slices
    val pftParams: Parameters = p.alterPartial {
      case EdgeInKey => node.in.head._2
      case EdgeOutKey => node.out.head._2
      case BankBitsKey => bankBits
    }
    val prefetcher = prefetchOpt.map(_ => Module(new Prefetcher()(pftParams)))
    val prefetchTrains = prefetchOpt.map(_ => Wire(Vec(banks, DecoupledIO(new PrefetchTrain()(pftParams)))))
    val prefetchResps = prefetchOpt.map(_ => Wire(Vec(banks, DecoupledIO(new PrefetchResp()(pftParams)))))
    val prefetchReqsReady = WireInit(VecInit(Seq.fill(banks)(false.B)))
    prefetchOpt.foreach {
      _ =>
        fastArb(prefetchTrains.get, prefetcher.get.io.train, Some("prefetch_train"))
        prefetcher.get.io.req.ready := Cat(prefetchReqsReady).orR
        fastArb(prefetchResps.get, prefetcher.get.io.resp, Some("prefetch_resp"))
    }
    pf_recv_node match {
      case Some(x) =>
        prefetcher.get.io.recv_addr.valid := x.in.head._1.addr_valid
        prefetcher.get.io.recv_addr.bits := x.in.head._1.addr
        prefetcher.get.io_l2_pf_en := x.in.head._1.l2_pf_en
      case None =>
        prefetcher.foreach(_.io.recv_addr := DontCare)
        prefetcher.foreach(_.io_l2_pf_en := DontCare)
    }

    def restoreAddress(x: UInt, idx: Int) = {
      restoreAddressUInt(x, idx.U)
    }
    def restoreAddressUInt(x: UInt, idx: UInt) = {
      if(bankBits == 0){
        x
      } else {
        val high = x >> offsetBits
        val low = x(offsetBits - 1, 0)
        Cat(high, idx(bankBits - 1, 0), low)
      }
    }
    def bank_eq(set: UInt, bankId: Int, bankBits: Int): Bool = {
      if(bankBits == 0) true.B else set(bankBits - 1, 0) === bankId.U
    }

    val slices = node.in.zip(node.out).zipWithIndex.map {
      case (((in, edgeIn), (out, edgeOut)), i) =>
        require(in.params.dataBits == out.params.dataBits)
        val rst_L2 = reset
        val slice = withReset(rst_L2) { 
          Module(new Slice()(p.alterPartial {
            case EdgeInKey  => edgeIn
            case EdgeOutKey => edgeOut
            case BankBitsKey => bankBits
          })) 
        }
        slice.io.in <> in
        in.b.bits.address := restoreAddress(slice.io.in.b.bits.address, i)
        out <> slice.io.out
        out.a.bits.address := restoreAddress(slice.io.out.a.bits.address, i)
        out.c.bits.address := restoreAddress(slice.io.out.c.bits.address, i)

        slice.io.prefetch.zip(prefetcher).foreach {
          case (s, p) =>
            s.req.valid := p.io.req.valid && bank_eq(p.io.req.bits.set, i, bankBits)
            s.req.bits := p.io.req.bits
            prefetchReqsReady(i) := s.req.ready && bank_eq(p.io.req.bits.set, i, bankBits)
            val train = Pipeline(s.train)
            val resp = Pipeline(s.resp)
            prefetchTrains.get(i) <> train
            prefetchResps.get(i) <> resp
            // restore to full address
            if(bankBits != 0){
              val train_full_addr = Cat(
                train.bits.tag, train.bits.set, i.U(bankBits.W), 0.U(offsetBits.W)
              )
              val (train_tag, train_set, _) = s.parseFullAddress(train_full_addr)
              val resp_full_addr = Cat(
                resp.bits.tag, resp.bits.set, i.U(bankBits.W), 0.U(offsetBits.W)
              )
              val (resp_tag, resp_set, _) = s.parseFullAddress(resp_full_addr)
              prefetchTrains.get(i).bits.tag := train_tag
              prefetchTrains.get(i).bits.set := train_set
              prefetchResps.get(i).bits.tag := resp_tag
              prefetchResps.get(i).bits.set := resp_set
            }
        }

        slice
    }

  }

}

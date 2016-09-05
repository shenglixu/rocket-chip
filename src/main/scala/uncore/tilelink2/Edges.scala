// See LICENSE for license details.

package uncore.tilelink2

import Chisel._
import chisel3.internal.sourceinfo.SourceInfo

class TLEdge(
  client:  TLClientPortParameters,
  manager: TLManagerPortParameters)
  extends TLEdgeParameters(client, manager)
{
  def isAligned(address: UInt, lgSize: UInt) =
    if (maxLgSize == 0) Bool(true) else {
      val mask = ~(SInt(-1, width=maxLgSize).asUInt << lgSize)(maxLgSize-1, 0)
      (address & mask) === UInt(0)
    }

  // This gets used everywhere, so make the smallest circuit possible ...
  def fullMask(address: UInt, lgSize: UInt) = {
    val lgBytes = log2Ceil(manager.beatBytes)
    val sizeOH = UIntToOH(lgSize, lgBytes)
    def helper(i: Int): Seq[(Bool, Bool)] = {
      if (i == 0) {
        Seq((lgSize >= UInt(lgBytes), Bool(true)))
      } else {
        val sub = helper(i-1)
        val size = sizeOH(lgBytes - i)
        val bit = address(lgBytes - i)
        val nbit = !bit
        Seq.tabulate (1 << i) { j =>
          val (sub_acc, sub_eq) = sub(j/2)
          val eq = sub_eq && (if (j % 2 == 1) bit else nbit)
          val acc = sub_acc || (size && eq)
          (acc, eq)
        }
      }
    }
    Cat(helper(lgBytes).map(_._1).reverse)
  }

  def lowAddress(mask: UInt) = {
    // Almost OHToUInt, but any bit in low => use low address
    def helper(mask: UInt, width: Int): UInt = {
      if (width <= 1) {
        UInt(0)
      } else if (width == 2) {
        ~mask(0, 0)
      } else {
        val mid = 1 << (log2Up(width)-1)
        val hi = mask(width-1, mid)
        val lo = mask(mid-1, 0)
        Cat(!lo.orR, helper(hi | lo, mid))
      }
    }
    helper(mask, bundle.dataBits/8)
  }

  def staticHasData(bundle: HasTLOpcode): Option[Boolean] = {
    bundle.channelType() match {
      case ChannelType.A => {
        // Do there exist A messages with Data?
        val aDataYes = manager.anySupportArithmetic || manager.anySupportLogical || manager.anySupportPutFull || manager.anySupportPutPartial
        // Do there exist A messages without Data?
        val aDataNo  = manager.anySupportAcquire || manager.anySupportGet || manager.anySupportHint
        // Statically optimize the case where hasData is a constant
        if (!aDataYes) Some(false) else if (!aDataNo) Some(true) else None
      }
      case ChannelType.B => {
        // Do there exist B messages with Data?
        val bDataYes = client.anySupportArithmetic || client.anySupportLogical || client.anySupportPutFull || client.anySupportPutPartial
        // Do there exist B messages without Data?
        val bDataNo  = client.anySupportProbe || client.anySupportGet || client.anySupportHint
        // Statically optimize the case where hasData is a constant
        if (!bDataYes) Some(false) else if (!bDataNo) Some(true) else None
      }
      case ChannelType.C => {
        // Do there eixst C messages with Data?
        val cDataYes = client.anySupportGet || client.anySupportArithmetic || client.anySupportLogical || client.anySupportProbe
        // Do there exist C messages without Data?
        val cDataNo  = client.anySupportPutFull || client.anySupportPutPartial || client.anySupportHint || client.anySupportProbe
        if (!cDataYes) Some(false) else if (!cDataNo) Some(true) else None
      }
      case ChannelType.D => {
        // Do there eixst D messages with Data?
        val dDataYes = manager.anySupportGet || manager.anySupportArithmetic || manager.anySupportLogical || manager.anySupportAcquire
        // Do there exist D messages without Data?
        val dDataNo  = manager.anySupportPutFull || manager.anySupportPutPartial || manager.anySupportHint || manager.anySupportAcquire
        if (!dDataYes) Some(false) else if (!dDataNo) Some(true) else None
      }
      case ChannelType.E => Some(false)
    }
  }

  def hasData(bundle: HasTLOpcode): Bool =
    staticHasData(bundle).map(Bool(_)).getOrElse(bundle.hasData())

  def numBeats(bundle: HasTLOpcode) = {
    val hasData = this.hasData(bundle)
    val size = bundle.size()
    val cutoff = log2Ceil(manager.beatBytes)
    val small = size <= UInt(cutoff)
    val decode = UIntToOH(size, maxLgSize+1) >> cutoff
    Mux(!hasData || small, UInt(1), decode)
  }
}

class TLEdgeOut(
  client:  TLClientPortParameters,
  manager: TLManagerPortParameters)
  extends TLEdge(client, manager)
{
  // Transfers
  def Acquire(fromSource: UInt, toAddress: UInt, lgSize: UInt, growPermissions: UInt) = {
    require (manager.anySupportAcquire)
    val legal = manager.supportsAcquire(toAddress, lgSize)
    val a = Wire(new TLBundleA(bundle))
    a.opcode  := TLMessages.Acquire
    a.param   := growPermissions
    a.size    := lgSize
    a.source  := fromSource
    a.address := toAddress
    a.mask    := SInt(-1).asUInt
    a.data    := UInt(0)
    (legal, a)
  }

  def Release(fromSource: UInt, toAddress: UInt, lgSize: UInt, shrinkPermissions: UInt) = {
    require (manager.anySupportAcquire)
    val legal = manager.supportsAcquire(toAddress, lgSize)
    val c = Wire(new TLBundleC(bundle))
    c.opcode  := TLMessages.Release
    c.param   := shrinkPermissions
    c.size    := lgSize
    c.source  := fromSource
    c.address := toAddress
    c.data    := UInt(0)
    c.error   := Bool(false)
    (legal, c)
  }

  def Release(fromSource: UInt, toAddress: UInt, lgSize: UInt, shrinkPermissions: UInt, data: UInt) = {
    require (manager.anySupportAcquire)
    val legal = manager.supportsAcquire(toAddress, lgSize)
    val c = Wire(new TLBundleC(bundle))
    c.opcode  := TLMessages.ReleaseData
    c.param   := shrinkPermissions
    c.size    := lgSize
    c.source  := fromSource
    c.address := toAddress
    c.data    := data
    c.error   := Bool(false)
    (legal, c)
  }

  def ProbeAck(toAddress: UInt, lgSize: UInt, reportPermissions: UInt) = {
    val c = Wire(new TLBundleC(bundle))
    c.opcode  := TLMessages.ProbeAck
    c.param   := reportPermissions
    c.size    := lgSize
    c.source  := UInt(0)
    c.address := toAddress
    c.data    := UInt(0)
    c.error   := Bool(false)
    c
  }

  def ProbeAck(toAddress: UInt, lgSize: UInt, reportPermissions: UInt, data: UInt) = {
    val c = Wire(new TLBundleC(bundle))
    c.opcode  := TLMessages.ProbeAckData
    c.param   := reportPermissions
    c.size    := lgSize
    c.source  := UInt(0)
    c.address := toAddress
    c.data    := data
    c.error   := Bool(false)
    c
  }

  def GrantAck(toSink: UInt) = {
    val e = Wire(new TLBundleE(bundle))
    e.sink := toSink
    e
  }

  // Accesses
  def Get(fromSource: UInt, toAddress: UInt, lgSize: UInt) = {
    require (manager.anySupportGet)
    val legal = manager.supportsGet(toAddress, lgSize)
    val a = Wire(new TLBundleA(bundle))
    a.opcode  := TLMessages.Get
    a.param   := UInt(0)
    a.size    := lgSize
    a.source  := fromSource
    a.address := toAddress
    a.mask    := fullMask(toAddress, lgSize)
    a.data    := UInt(0)
    (legal, a)
  }

  def Put(fromSource: UInt, toAddress: UInt, lgSize: UInt, data: UInt) = {
    require (manager.anySupportPutFull)
    val legal = manager.supportsPutFull(toAddress, lgSize)
    val a = Wire(new TLBundleA(bundle))
    a.opcode  := TLMessages.PutFullData
    a.param   := UInt(0)
    a.size    := lgSize
    a.source  := fromSource
    a.address := toAddress
    a.mask    := fullMask(toAddress, lgSize)
    a.data    := data
    (legal, a)
  }

  def Put(fromSource: UInt, toAddress: UInt, lgSize: UInt, data: UInt, mask : UInt) = {
    require (manager.anySupportPutPartial)
    val legal = manager.supportsPutPartial(toAddress, lgSize)
    val a = Wire(new TLBundleA(bundle))
    a.opcode  := TLMessages.PutPartialData
    a.param   := UInt(0)
    a.size    := lgSize
    a.source  := fromSource
    a.address := toAddress
    a.mask    := mask
    a.data    := data
    (legal, a)
  }

  def Arithmetic(fromSource: UInt, toAddress: UInt, lgSize: UInt, data: UInt, atomic: UInt) = {
    require (manager.anySupportArithmetic)
    val legal = manager.supportsArithmetic(toAddress, lgSize)
    val a = Wire(new TLBundleA(bundle))
    a.opcode  := TLMessages.ArithmeticData
    a.param   := atomic
    a.size    := lgSize
    a.source  := fromSource
    a.address := toAddress
    a.mask    := fullMask(toAddress, lgSize)
    a.data    := data
    (legal, a)
  }

  def Logical(fromSource: UInt, toAddress: UInt, lgSize: UInt, data: UInt, atomic: UInt) = {
    require (manager.anySupportLogical)
    val legal = manager.supportsLogical(toAddress, lgSize)
    val a = Wire(new TLBundleA(bundle))
    a.opcode  := TLMessages.LogicalData
    a.param   := atomic
    a.size    := lgSize
    a.source  := fromSource
    a.address := toAddress
    a.mask    := fullMask(toAddress, lgSize)
    a.data    := data
    (legal, a)
  }

  def Hint(fromSource: UInt, toAddress: UInt, lgSize: UInt, param: UInt) = {
    require (manager.anySupportHint)
    val legal = manager.supportsHint(toAddress)
    val a = Wire(new TLBundleA(bundle))
    a.opcode  := TLMessages.Hint
    a.param   := param
    a.size    := lgSize
    a.source  := fromSource
    a.address := toAddress
    a.mask    := fullMask(toAddress, lgSize)
    a.data    := UInt(0)
    (legal, a)
  }

  def AccessAck(toAddress: UInt, lgSize: UInt): TLBundleC = AccessAck(toAddress, lgSize, Bool(false))
  def AccessAck(toAddress: UInt, lgSize: UInt, error: Bool) = {
    val c = Wire(new TLBundleC(bundle))
    c.opcode  := TLMessages.AccessAck
    c.param   := UInt(0)
    c.size    := lgSize
    c.source  := UInt(0)
    c.address := toAddress
    c.data    := UInt(0)
    c.error   := error
    c
  }

  def AccessAck(toAddress: UInt, lgSize: UInt, data: UInt): TLBundleC = AccessAck(toAddress, lgSize, data, Bool(false))
  def AccessAck(toAddress: UInt, lgSize: UInt, data: UInt, error: Bool) = {
    val c = Wire(new TLBundleC(bundle))
    c.opcode  := TLMessages.AccessAckData
    c.param   := UInt(0)
    c.size    := lgSize
    c.source  := UInt(0)
    c.address := toAddress
    c.data    := data
    c.error   := error
    c
  }

  def HintAck(toAddress: UInt, lgSize: UInt) = {
    val c = Wire(new TLBundleC(bundle))
    c.opcode  := TLMessages.HintAck
    c.param   := UInt(0)
    c.size    := lgSize
    c.source  := UInt(0)
    c.address := toAddress
    c.data    := UInt(0)
    c.error   := Bool(false)
    c
  }
}

class TLEdgeIn(
  client:  TLClientPortParameters,
  manager: TLManagerPortParameters)
  extends TLEdge(client, manager)
{
  // Transfers
  def Probe(fromAddress: UInt, toSource: UInt, lgSize: UInt, capPermissions: UInt) = {
    require (client.anySupportProbe)
    val legal = client.supportsProbe(fromAddress, lgSize)
    val b = Wire(new TLBundleB(bundle))
    b.opcode  := TLMessages.Probe
    b.param   := capPermissions
    b.size    := lgSize
    b.source  := toSource
    b.address := fromAddress
    b.mask    := SInt(-1).asUInt
    b.data    := UInt(0)
    (legal, b)
  }

  def Grant(fromSink: UInt, toSource: UInt, lgSize: UInt, capPermissions: UInt): TLBundleD = Grant(fromSink, toSource, lgSize, capPermissions, Bool(false))
  def Grant(fromSink: UInt, toSource: UInt, lgSize: UInt, capPermissions: UInt, error: Bool) = {
    val d = Wire(new TLBundleD(bundle))
    d.opcode := TLMessages.Grant
    d.param  := capPermissions
    d.size   := lgSize
    d.source := toSource
    d.sink   := fromSink
    d.data   := UInt(0)
    d.error  := error
    d
  }

  def Grant(fromSink: UInt, toSource: UInt, lgSize: UInt, capPermissions: UInt, data: UInt): TLBundleD = Grant(fromSink, toSource, lgSize, capPermissions, data, Bool(false))
  def Grant(fromSink: UInt, toSource: UInt, lgSize: UInt, capPermissions: UInt, data: UInt, error: Bool) = {
    val d = Wire(new TLBundleD(bundle))
    d.opcode := TLMessages.GrantData
    d.param  := capPermissions
    d.size   := lgSize
    d.source := toSource
    d.sink   := fromSink
    d.data   := data
    d.error  := error
    d
  }

  def ReleaseAck(toSource: UInt, lgSize: UInt) = {
    val d = Wire(new TLBundleD(bundle))
    d.opcode := TLMessages.ReleaseAck
    d.param  := UInt(0)
    d.size   := lgSize
    d.source := toSource
    d.sink   := UInt(0)
    d.data   := UInt(0)
    d.error  := Bool(false)
    d
  }

  // Accesses
  def Get(fromAddress: UInt, toSource: UInt, lgSize: UInt) = {
    require (client.anySupportGet)
    val legal = client.supportsGet(toSource, lgSize)
    val b = Wire(new TLBundleB(bundle))
    b.opcode  := TLMessages.Get
    b.param   := UInt(0)
    b.size    := lgSize
    b.source  := toSource
    b.address := fromAddress
    b.mask    := fullMask(fromAddress, lgSize)
    b.data    := UInt(0)
    (legal, b)
  }

  def Put(fromAddress: UInt, toSource: UInt, lgSize: UInt, data: UInt) = {
    require (client.anySupportPutFull)
    val legal = client.supportsPutFull(toSource, lgSize)
    val b = Wire(new TLBundleB(bundle))
    b.opcode  := TLMessages.PutFullData
    b.param   := UInt(0)
    b.size    := lgSize
    b.source  := toSource
    b.address := fromAddress
    b.mask    := fullMask(fromAddress, lgSize)
    b.data    := data
    (legal, b)
  }

  def Put(fromAddress: UInt, toSource: UInt, lgSize: UInt, data: UInt, mask : UInt) = {
    require (client.anySupportPutPartial)
    val legal = client.supportsPutPartial(toSource, lgSize)
    val b = Wire(new TLBundleB(bundle))
    b.opcode  := TLMessages.PutPartialData
    b.param   := UInt(0)
    b.size    := lgSize
    b.source  := toSource
    b.address := fromAddress
    b.mask    := mask
    b.data    := data
    (legal, b)
  }

  def Arithmetic(fromAddress: UInt, toSource: UInt, lgSize: UInt, data: UInt, atomic: UInt) = {
    require (client.anySupportArithmetic)
    val legal = client.supportsArithmetic(toSource, lgSize)
    val b = Wire(new TLBundleB(bundle))
    b.opcode  := TLMessages.ArithmeticData
    b.param   := atomic
    b.size    := lgSize
    b.source  := toSource
    b.address := fromAddress
    b.mask    := fullMask(fromAddress, lgSize)
    b.data    := data
    (legal, b)
  }

  def Logical(fromAddress: UInt, toSource: UInt, lgSize: UInt, data: UInt, atomic: UInt) = {
    require (client.anySupportLogical)
    val legal = client.supportsLogical(toSource, lgSize)
    val b = Wire(new TLBundleB(bundle))
    b.opcode  := TLMessages.LogicalData
    b.param   := atomic
    b.size    := lgSize
    b.source  := toSource
    b.address := fromAddress
    b.mask    := fullMask(fromAddress, lgSize)
    b.data    := data
    (legal, b)
  }

  def Hint(fromAddress: UInt, toSource: UInt, lgSize: UInt, param: UInt) = {
    require (client.anySupportHint)
    val legal = client.supportsHint(toSource)
    val b = Wire(new TLBundleB(bundle))
    b.opcode  := TLMessages.Hint
    b.param   := param
    b.size    := lgSize
    b.source  := toSource
    b.address := fromAddress
    b.mask    := fullMask(fromAddress, lgSize)
    b.data    := UInt(0)
    (legal, b)
  }

  def AccessAck(toSource: UInt, lgSize: UInt): TLBundleD = AccessAck(toSource, lgSize, Bool(false))
  def AccessAck(toSource: UInt, lgSize: UInt, error: Bool) = {
    val d = Wire(new TLBundleD(bundle))
    d.opcode := TLMessages.AccessAck
    d.param  := UInt(0)
    d.size   := lgSize
    d.source := toSource
    d.sink   := UInt(0)
    d.data   := UInt(0)
    d.error  := error
    d
  }

  def AccessAck(toSource: UInt, lgSize: UInt, data: UInt): TLBundleD = AccessAck(toSource, lgSize, data, Bool(false))
  def AccessAck(toSource: UInt, lgSize: UInt, data: UInt, error: Bool) = {
    val d = Wire(new TLBundleD(bundle))
    d.opcode := TLMessages.AccessAckData
    d.param  := UInt(0)
    d.size   := lgSize
    d.source := toSource
    d.sink   := UInt(0)
    d.data   := data
    d.error  := error
    d
  }

  def HintAck(toSource: UInt, lgSize: UInt) = {
    val d = Wire(new TLBundleD(bundle))
    d.opcode := TLMessages.HintAck
    d.param  := UInt(0)
    d.size   := lgSize
    d.source := toSource
    d.sink   := UInt(0)
    d.data   := UInt(0)
    d.error  := Bool(false)
    d
  }
}

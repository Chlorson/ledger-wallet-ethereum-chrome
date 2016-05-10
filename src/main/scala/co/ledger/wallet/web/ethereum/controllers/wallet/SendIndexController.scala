package co.ledger.wallet.web.ethereum.controllers.wallet

import biz.enef.angulate.Controller
import biz.enef.angulate.Module.RichModule
import biz.enef.angulate.core.JQLite
import co.ledger.wallet.web.ethereum.services.WindowService

import scala.scalajs.js

/**
  *
  * SendIndexController
  * ledger-wallet-ethereum-chrome
  *
  * Created by Pierre Pollastri on 04/05/2016.
  *
  * The MIT License (MIT)
  *
  * Copyright (c) 2016 Ledger
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  *
  */
class SendIndexController(override val windowService: WindowService, $location: js.Dynamic, $element: JQLite) extends Controller with WalletController{

  def scanQrCode() = {

  }

  def send() = {
    try {
      val amount = BigDecimal($element.find("#amount_input").asInstanceOf[JQLite].`val`().toString)
      val recipient = $element.find("#receiver_input").asInstanceOf[JQLite].`val`().toString
      val isIban = true
      val fees = BigDecimal(0)
      println(s"Amount: $amount")
      println(s"Recipient: $recipient")
      println(s"Is IBAN: $isIban")
      println(s"Fees: $fees")
      val formattedRecipient = recipient
      $location.path(s"/send/$amount/to/$formattedRecipient/from/0/with/$fees")
    } catch {
      case any: Throwable =>
        any.printStackTrace()
        // Display error message
    }
    //
  }

}

object SendIndexController {
  def init(module: RichModule) = module.controllerOf[SendIndexController]("SendIndexController")
}
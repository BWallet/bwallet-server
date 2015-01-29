package com.bdx.bwallet.server.web.controller;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.bdx.bwallet.server.broadcast.BroadcastService;
import com.bdx.bwallet.server.core.WalletService;
import com.bdx.bwallet.server.web.controller.model.SendForm;

@Controller
@RequestMapping("/trezor")
public class TransactionController {

	@Autowired
	private WalletService walletService;
	
	@Autowired
	private BroadcastService broadcastService;
	
	@ResponseBody
	@RequestMapping(value = "/{xpub}/transactions", method = RequestMethod.GET)
	public Object transactions(HttpServletRequest request, HttpServletResponse response, Model model,
			@PathVariable("xpub") String xpub) {
		return walletService.getTXs(xpub);
	}

	@ResponseBody
	@RequestMapping(value = "/{xpub}/transactions/{hash}", method = RequestMethod.GET)
	public Object transaction(HttpServletRequest request, HttpServletResponse response, Model model,
			@PathVariable("xpub") String xpub, @PathVariable("hash") String hash) {
		return walletService.getTX(xpub, hash);
	}

	@ResponseBody
	@RequestMapping(value = "/send", method = RequestMethod.POST, headers = { "Content-type=application/json" })
	public ResponseEntity<Object> send(HttpServletRequest request, HttpServletResponse response, Model model, @RequestBody SendForm form) {
		byte [] tx = Base64.decodeBase64(form.getTransaction());
		ResponseEntity<Object> responseEntity = null;
		try {
			if (broadcastService.isRunning()) {
				broadcastService.broadcast(tx, form.getPublicMaster());
			} else {
				walletService.send(tx);
			}
			responseEntity = new ResponseEntity<Object>(HttpStatus.OK);
		} catch (Exception ex) {
			ex.printStackTrace();
			HttpHeaders headers = new HttpHeaders();
			Map<String, String> message = new HashMap<String, String>();
			message.put("message", ex.getMessage());
			responseEntity = new ResponseEntity<Object>(message, headers, HttpStatus.INTERNAL_SERVER_ERROR);
		}
		return responseEntity;
	}

}

package com.bdx.bwallet.server.web.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.bdx.bwallet.server.core.WalletService;
import com.bdx.bwallet.server.core.model.WalletAccoutStatus;
import com.bdx.bwallet.server.web.controller.model.SubscribeForm;

@Controller
@RequestMapping("/lp")
public class LongPollController {

	@Autowired
	private WalletService walletService;
	
	@ResponseBody
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping("")
	public Object create(HttpServletRequest request, HttpServletResponse response, Model model) {
		String clientId = walletService.createConnection();
		Map<String, String> map = new HashMap<String, String>();
		map.put("clientId", clientId);
		return map;
	}

	@RequestMapping(value = "/{clientId}", method = RequestMethod.GET)
	public ResponseEntity<Object> poll(HttpServletRequest request, HttpServletResponse response, Model model,
			@PathVariable("clientId") String clientId) {
		WalletAccoutStatus[] statusArray = walletService.poll(clientId, 50, TimeUnit.SECONDS);
		if (statusArray == null || statusArray.length == 0) {
			ResponseEntity<Object> responseEntity = new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
			return responseEntity;
		} else {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			ResponseEntity<Object> responseEntity = new ResponseEntity<Object>(statusArray, headers, HttpStatus.OK);
			return responseEntity;
		}
	}

	@ResponseBody
	@RequestMapping(value = "/{clientId}", method = RequestMethod.POST, headers = { "Content-type=application/json" })
	public Object subscribe(HttpServletRequest request, HttpServletResponse response, Model model,
			@PathVariable("clientId") String clientId, @RequestBody SubscribeForm form) {
		WalletAccoutStatus status = walletService.subscribe(clientId, form.getPublicMaster(), form.getAfter(),
				form.getLookAhead(), form.getFirstIndex());
		return status;
	}

}

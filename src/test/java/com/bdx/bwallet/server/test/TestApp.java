package com.bdx.bwallet.server.test;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.ExecutionException;

import org.apache.commons.codec.binary.Hex;
import org.bitcoinj.core.AddressFormatException;
import org.spongycastle.util.encoders.Base64;


public class TestApp {
	
	static class TestBean {
		private String a;
		private String b;
		
		public String getA() {
			return a;
		}
		public void setA(String a) {
			this.a = a;
		}
		public void setB(String b) {
			this.b = b;
		}
		public String getB() {
			return b;
		}
		public TestBean(){
		}
		public TestBean(String a, String b) {
			this.a = a;
			this.b = b;
		}
		public String toString(){
			return a + b;
		}
	}
	
	public static void main(String[] args) throws InterruptedException, ExecutionException, AddressFormatException, UnsupportedEncodingException {
		/*
		DeterministicKey masterKey = DeterministicKey
				.deserializeB58(null,
						"xpub6BmuF6piZw28UTSuuhRGD96EGiRnp9oihRXPu93aYRd1NUzytTDiqUM8s3UEKttnxXvqAFWZrmPGz9MzLYMC57TATXugRNtuCHdeYRUxYZZ");
		DeterministicHierarchy masterKeyHierarchy = new DeterministicHierarchy(masterKey);
		List<ChildNumber> externalPath = Arrays.asList(new ChildNumber[] { new ChildNumber(0, false) });
		DeterministicKey key = masterKeyHierarchy.deriveChild(externalPath, true, true, new ChildNumber(0, false));
		System.out.println(key.getPath());
		*/
		
		/*
		SettableFuture<String> future = SettableFuture.create();
		
		Futures.addCallback(future, new FutureCallback<Object>() {
			public void onSuccess(Object result) {
				System.out.println("onSuccess");
			}
			public void onFailure(Throwable thrown) {
				thrown.printStackTrace();
				System.out.println("onFailure");
			}
		});
		future.set("xx");
		System.out.println(future.cancel(true));
		System.out.println(future.get());
		System.out.println(future.isCancelled());
		*/
		
		/*
		Map<String, String> map = new ConcurrentHashMap<String, String>();
		map.put("1", "a");
		map.put("2", "b");
		map.put("3", "c");
		map.put("4", "d");
		map.put("5", "e");
		map.put("6", "f");
		map.put("7", "g");
		Iterator<String> iterator = map.keySet().iterator();
		while (iterator.hasNext()) {
			String key = iterator.next();
			System.out.println(key);
			map.remove(key);
		}*/
		
		/*
		TestBean a = new TestBean("xx", "yy");
		TestBean b = new TestBean();
		BeanUtils.copyProperties(a, b, new String[]{});
		System.out.println(a.toString());
		System.out.println(b.toString());
		*/
		
		/*
		List<ChildNumber> path = Arrays.asList(new ChildNumber[] { new ChildNumber(1, false) });
		
		String publicMaster ="xpub6BmuF6piZw28UTSuuhRGD96EGiRnp9oihRXPu93aYRd1NUzytTDiqUM8s3UEKttnxXvqAFWZrmPGz9MzLYMC57TATXugRNtuCHdeYRUxYZZ";
		DeterministicKey masterKey = DeterministicKey.deserializeB58(null, publicMaster);
		DeterministicHierarchy masterKeyHierarchy = new DeterministicHierarchy(masterKey);
		for (int i = 0; i < 30; i++) {
			DeterministicKey key = masterKeyHierarchy.deriveChild(path, true, true, new ChildNumber(i, false));
			System.out.println("i " + i + " - " + key.toAddress(MainNetParams.get()));
		}
		
		path = Arrays.asList(new ChildNumber[] { new ChildNumber(0, false) });
		
		masterKey = DeterministicKey.deserializeB58(null, publicMaster);
		masterKeyHierarchy = new DeterministicHierarchy(masterKey);
		for (int i = 0; i < 30; i++) {
			DeterministicKey key = masterKeyHierarchy.deriveChild(path, true, true, new ChildNumber(i, false));
			System.out.println("e " + i + " - " + key.toAddress(MainNetParams.get()));
		}
		*/
		
		/*
		SettableFuture<Boolean> future = SettableFuture.create();
		Futures.addCallback(future, new FutureCallback<Object>() {
			public void onSuccess(Object result) {
				System.out.println("onSuccess");
			}
			public void onFailure(Throwable thrown) {
				System.out.println("onFailure");
			}
		});
		System.out.println(future.cancel(true));
		System.out.println(future.set(true));
		*/
		
//		Map<Integer, String> map = new HashMap<Integer, String>();
//		map.put(1, "xx");
//		System.out.println(map.size());
//		
//		int key = (int)1;
//		map.remove(key);
//		System.out.println(map.size());
		
		byte[] bytes = Base64.decode("L4JHLLchVP47W9Lo");
		System.out.println(Hex.encodeHexString(bytes));
	}

}

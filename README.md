# EdgeSwarm Decentralized AI Network
**Node Infrastructure Documentation**

EdgeSwarm is a Web2.5 Decentralized Physical Infrastructure Network (DePIN) designed to execute distributed AI inference, web scraping, and tensor math across a global swarm of consumer devices. The network utilizes an off-chain mempool for high-speed task routing and settles consensus and $SWARM token payouts on-chain via the Base Sepolia blockchain.

This repository contains the architecture and deployment instructions for the two primary worker nodes in the ecosystem: the **Headless Android Node** and the **Desktop/Laptop Node**.

---

## 📱 1. Android Node (Mobile Worker)

The Android node operates as a highly optimized, headless background service capable of executing genuine offline Large Language Model (LLM) inference and secure web scraping.

### Minimum Hardware Requirements
To ensure network stability and prevent Out-Of-Memory (OOM) operating system crashes, mobile devices must meet the following specifications:
* **OS:** Android 8.0 (Oreo) or newer (API Level 26+)
* **RAM:** 8GB minimum (12GB+ highly recommended)
* **Processor:** 64-bit architecture (ARM64-v8a)
* **Storage:** 3GB+ free internal storage (for the Gemma 1.5GB model file and swap space)

### Key Features
* **MediaPipe AI Engine:** Runs Google's `gemma-2b-it-cpu-int4.bin` model entirely offline.
* **Safe Chunking:** Bypasses Android's aggressive memory management by streaming the 1.5GB model into internal storage via a 4MB chunked buffer.
* **Web3j Integration:** Autonomously generates ECDSA key pairs and cryptographically signs payloads (`Task:ID|Score:100|Hash:HASH|HW:ID`) before returning them to the enterprise server.
* **Smart Routing:** Identifies as `PIX10_...` (or equivalent device ID) to intercept mobile-friendly tasks like secure HTML web scraping.

### Installation & Deployment
1. Download the compiled `app-release.apk` to your Android device.
2. Grant **Notification Permissions** (required for the Foreground Service to persist).
3. Open the app, log in, and click **Activate Headless Mode**.
4. The node will sync its Web3 wallet, unpack the AI model (takes ~60 seconds on first boot), and begin polling the mempool.

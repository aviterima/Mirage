#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <fstream>
#include <iostream>
#ifndef MIRAGE_CLI
#include <jni.h>
#endif

static std::mutex gate;
static std::atomic<bool> cancelled{false};
static llama_model * model = nullptr;
static std::string modelPath;
static auto deadline = std::chrono::steady_clock::now();
static bool abortInference(void *) { return cancelled.load() || std::chrono::steady_clock::now() > deadline; }
static const char * grammar = R"GBNF(
root ::= "{" ws "\"action\"" ws ":" ws action "," ws "\"target\"" ws ":" ws string "," ws "\"placement\"" ws ":" ws placement "," ws "\"minutes\"" ws ":" ws number "," ws "\"position\"" ws ":" ws number ws "}"
action ::= "\"add_saved\"" | "\"add_place\"" | "\"save_new\"" | "\"save_changes\"" | "\"move\"" | "\"remove\"" | "\"stay\"" | "\"extend\"" | "\"status\"" | "\"pause\"" | "\"resume\"" | "\"clarify\""
placement ::= "\"NEXT\"" | "\"END\"" | "\"NOW\""
number ::= [0-9]{1,4}
string ::= "\"" char{0,160} "\""
char ::= [^"\\\x00-\x1F] | "\\" ["\\/bfnrt] | "\\u" [0-9a-fA-F]{4}
ws ::= [ \t\n]{0,4}
)GBNF";

static std::string infer(const std::string & path, const std::string & prompt) {
    std::lock_guard<std::mutex> lock(gate);
    cancelled = false;
    deadline = std::chrono::steady_clock::now() + std::chrono::seconds(45);
    if (!model || modelPath != path) {
        if (model) llama_model_free(model);
        llama_backend_init();
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        model = llama_model_load_from_file(path.c_str(), mp); modelPath = path;
        if (!model) throw std::runtime_error("Could not load the offline language model");
    }
    auto vocab = llama_model_get_vocab(model);
    int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (count <= 0 || count > 1750) throw std::runtime_error("Instruction context is too long; use a shorter request");
    std::vector<llama_token> tokens(count);
    llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true);
    auto cp = llama_context_default_params(); cp.n_ctx = 2048; cp.n_batch = 512; cp.n_ubatch = 256;
    cp.n_threads = 4; cp.n_threads_batch = 4; cp.abort_callback = abortInference;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model, cp), llama_free);
    if (!ctx) throw std::runtime_error("Not enough memory for offline understanding");
    for (int i = 0; i < count; i += 512) {
        if (abortInference(nullptr) || llama_decode(ctx.get(), llama_batch_get_one(tokens.data()+i, std::min(512,count-i))) != 0)
            throw std::runtime_error("Offline understanding timed out or was cancelled");
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    auto g = llama_sampler_init_grammar(vocab, grammar, "root");
    if (!g) throw std::runtime_error("Cannot initialize command grammar");
    llama_sampler_chain_add(sampler.get(), g);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    std::string out;
    for (int i = 0; i < 220; ++i) {
        if (abortInference(nullptr)) throw std::runtime_error("Offline understanding timed out or was cancelled");
        auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) return out;
        char piece[2048]; int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (n < 0) throw std::runtime_error("Invalid language-model output");
        out.append(piece,n);
        if (llama_decode(ctx.get(), llama_batch_get_one(&token,1)) != 0) throw std::runtime_error("Offline understanding interrupted");
    }
    throw std::runtime_error("Instruction is too complex; try one change at a time");
}
#ifdef MIRAGE_CLI
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    std::ifstream f(argv[2]); std::string p((std::istreambuf_iterator<char>(f)),{});
    try { std::cout << infer(argv[1],p) << std::endl; } catch (const std::exception &e) { std::cerr << e.what(); return 1; }
    return 0;
}
#else
extern "C" JNIEXPORT jstring JNICALL Java_com_mirage_spike_LocalLanguageModel_generate(JNIEnv *env,jobject,jstring path,jstring prompt) {
    const char *p=env->GetStringUTFChars(path,nullptr); std::string file(p); env->ReleaseStringUTFChars(path,p);
    const char *q=env->GetStringUTFChars(prompt,nullptr); std::string input(q); env->ReleaseStringUTFChars(prompt,q);
    try { return env->NewStringUTF(infer(file,input).c_str()); }
    catch (const std::exception &e) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_mirage_spike_LocalLanguageModel_cancelNative(JNIEnv *,jobject) { cancelled=true; }
extern "C" JNIEXPORT void JNICALL Java_com_mirage_spike_LocalLanguageModel_releaseNative(JNIEnv *,jobject) {
    std::lock_guard<std::mutex> lock(gate); if (model) llama_model_free(model); model=nullptr;
}
#endif

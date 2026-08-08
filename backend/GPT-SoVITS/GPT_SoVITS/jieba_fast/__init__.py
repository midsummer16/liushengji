# Shim package: jieba_fast is a Cython fork of jieba with no py3.12 wheels.
# GPT-SoVITS imports `jieba_fast as jieba`; transparently fall back to pure-python jieba.
from jieba import (
    dt,
    lcut,
    cut,
    cut_for_search,
    tokenize,
    add_word,
    set_dictionary,
    load_userdict,
    suggest_freq,
    initialize,
    enable_paddle,
    get_FREQ,
    setLogLevel,
)
from jieba import posseg

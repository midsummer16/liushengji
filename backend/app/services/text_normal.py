import re
import logging

try:
    import cn2an
except ImportError:
    cn2an = None

logger = logging.getLogger("TextNormalizer")


class TextNormalizer:

    @staticmethod
    def normalize_numbers(text: str) -> str:
        """
        Converts Arabic digits to spoken Chinese words.
        e.g. "2024" -> "二零二四", "12" -> "十二"
        """
        if cn2an is None:
            return text

        def replace_num(match):
            num_str = match.group(0)
            try:
                if len(num_str) > 4:
                    return cn2an.an2cn(num_str, mode="direct")
                else:
                    return cn2an.an2cn(num_str, mode="low")
            except Exception:
                return num_str

        return re.sub(r"\d+", replace_num, text)

    @staticmethod
    def split_sentences(text: str) -> list[str]:
        """
        Splits long text into TTS-friendly sentences based on punctuation.
        Reduces initial synthesis delay (TTFB).
        """
        if not text:
            return []

        # Replace continuous punctuation or newlines
        cleaned = re.sub(r"[\r\n]+", "。", text)
        sentences = re.split(r"(?<=[。！？；!?;\n])", cleaned)

        result = []
        for s in sentences:
            s_str = s.strip()
            if s_str:
                if len(s_str) > 30:
                    sub_parts = re.split(r"(?<=[,，])", s_str)
                    for sub in sub_parts:
                        sub_str = sub.strip()
                        if sub_str:
                            result.append(sub_str)
                else:
                    result.append(s_str)

        return result if result else [text]

    @classmethod
    def process(cls, text: str) -> list[str]:
        normalized = cls.normalize_numbers(text)
        sentences = cls.split_sentences(normalized)
        return sentences

from abc import ABC, abstractmethod
from typing import Iterator


class BaseReader(ABC):

    @abstractmethod
    def read_lines(self) -> Iterator[str]:
        """Yields one scan line per USB read event, blocking until data arrives."""
        ...

    def close(self) -> None:
        """Release device resources. Safe to call more than once."""
        pass

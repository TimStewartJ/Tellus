"""Check DH class/member references in a precompiled Iris jar against a DH jar."""
import struct
import sys
import zipfile
from functools import lru_cache


class ClassFile:
    def __init__(self, data):
        self.data, self.pos = data, 8
        self.pool = [None] * self.u2()
        i = 1
        while i < len(self.pool):
            tag = self.take(1)[0]
            if tag == 1:
                self.pool[i] = self.take(self.u2()).decode('utf-8', errors='replace')
            elif tag in (7, 8, 16, 19, 20):
                self.pool[i] = (tag, self.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                self.pool[i] = (tag, self.u2(), self.u2())
            elif tag in (3, 4):
                self.take(4)
            elif tag in (5, 6):
                self.take(8)
                i += 1
            elif tag == 15:
                self.take(3)
            else:
                raise ValueError('Unknown constant pool tag: ' + str(tag))
            i += 1
        self.access = self.u2()
        self.name = self.class_name(self.u2())
        self.parent = self.class_name(self.u2())
        self.interfaces = [self.class_name(self.u2()) for _ in range(self.u2())]
        self.member_access = {}
        self.fields = self.members()
        self.methods = self.members()

    def take(self, size):
        result = self.data[self.pos:self.pos + size]
        self.pos += size
        return result

    def u2(self):
        return struct.unpack('>H', self.take(2))[0]

    def class_name(self, index):
        return self.pool[self.pool[index][1]] if index else None

    def members(self):
        result = set()
        for _ in range(self.u2()):
            access, name, descriptor = self.u2(), self.u2(), self.u2()
            result.add((self.pool[name], self.pool[descriptor]))
            self.member_access[(self.pool[name], self.pool[descriptor])] = access
            for _ in range(self.u2()):
                self.u2()
                self.take(struct.unpack('>I', self.take(4))[0])
        return result

    def dh_refs(self):
        for item in self.pool:
            if isinstance(item, tuple) and item[0] in (9, 10, 11):
                owner = self.class_name(item[1])
                if owner.startswith('com/seibel/distanthorizons/'):
                    nt = self.pool[item[2]]
                    yield item[0], owner, self.pool[nt[1]], self.pool[nt[2]]
            elif isinstance(item, tuple) and item[0] == 7:
                name = self.pool[item[1]]
                if name.startswith('com/seibel/distanthorizons/'):
                    yield 7, name, '', ''


def main():
    with zipfile.ZipFile(sys.argv[1]) as iris, zipfile.ZipFile(sys.argv[2]) as dh:
        refs = set()
        for name in iris.namelist():
            if name.endswith('.class'):
                refs.update(ClassFile(iris.read(name)).dh_refs())

        @lru_cache(None)
        def read_class(name):
            try:
                return ClassFile(dh.read(name + '.class'))
            except KeyError:
                return None

        def resolves(tag, owner, name, desc):
            cls = read_class(owner)
            if cls is None:
                return False
            if tag == 7:
                return True
            if (name, desc) in (cls.fields if tag == 9 else cls.methods):
                return True
            if name == '<init>':
                return False
            return any(resolves(tag, parent, name, desc)
                       for parent in [cls.parent, *cls.interfaces] if parent)

        missing = sorted(ref for ref in refs if not resolves(*ref))
        print(f'{len(refs)} DH class/member references checked in {sys.argv[1]}')
        print(f'{len(missing)} unresolved against {sys.argv[2]}')
        for ref in missing:
            print('MISSING:', *ref)
        return bool(missing)


if __name__ == '__main__':
    sys.exit(main())

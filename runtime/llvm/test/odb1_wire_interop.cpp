// ODB1 native read-only conformance test executable, NOT an Oreslang LLVM guest.
// Independent implementation of the Java ReadOnlyWalReplay inspection contract.
#include <charconv>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace {
using Byte = std::uint8_t;
using Bytes = std::vector<Byte>;
constexpr std::size_t max_bytes = 1048576;
constexpr std::size_t max_records = 4096;
constexpr std::size_t header_bytes = 26;
constexpr std::size_t trailer_bytes = 4;
struct Reject {
  std::string_view reason;
  std::size_t offset;
};
struct Result { std::size_t records; std::size_t bytes; std::uint32_t sequence; std::uint32_t generation; };
std::uint16_t u16(std::span<const Byte> d, std::size_t off) {
  return static_cast<std::uint16_t>(static_cast<std::uint16_t>(d[off]) |
         static_cast<std::uint16_t>(d[off + 1]) << 8);
}
std::uint32_t u32(std::span<const Byte> d, std::size_t off) {
  return std::uint32_t(d[off]) | (std::uint32_t(d[off + 1]) << 8) |
         (std::uint32_t(d[off + 2]) << 16) | (std::uint32_t(d[off + 3]) << 24);
}
std::uint32_t i31(std::span<const Byte> d, std::size_t off, std::size_t base) {
  if (d[off + 3] > 127) throw Reject{"high_bit_identity", base};
  return u32(d, off);
}
Result inspect(std::span<const Byte> d, std::uint32_t tenant) {
  if (tenant == 0 || tenant > 2147483647U) throw std::invalid_argument("trusted tenant out of range");
  if (d.size() > max_bytes) throw Reject{"wal_size_out_of_bounds", 0};
  std::unordered_set<std::uint32_t> operations;
  std::size_t at = 0, records = 0;
  std::uint32_t last_sequence = 0, last_generation = 0;
  while (at < d.size()) {
    if (records == max_records) throw Reject{"too_many_records", at};
    const std::size_t left = d.size() - at;
    if (left < header_bytes) throw Reject{"unverified_short_tail", at};
    if (d[at] != 79 || d[at + 1] != 68 || d[at + 2] != 66 ||
        d[at + 3] != 49 || d[at + 4] != 1) throw Reject{"invalid_magic_or_version", at};
    const auto kind = d[at + 5];
    if (kind != 1 && kind != 2) throw Reject{"invalid_kind", at};
    const std::uint32_t wire_tenant = i31(d, at + 6, at);
    const std::uint32_t generation = i31(d, at + 10, at);
    const std::uint32_t sequence = i31(d, at + 14, at);
    const std::uint32_t operation = i31(d, at + 18, at);
    if (wire_tenant != tenant || wire_tenant == 0 || generation == 0 ||
        sequence == 0 || operation == 0) throw Reject{"invalid_identity_or_tenant", at};
    if (generation < last_generation) throw Reject{"generation_regression", at};
    if (last_sequence == 2147483647U || sequence != last_sequence + 1)
      throw Reject{"sequence_gap", at};
    if (operations.contains(operation)) throw Reject{"duplicate_operation", at};
    const std::uint16_t klen = u16(d, at + 22), vlen = u16(d, at + 24);
    if (klen < 1 || klen > 64 || vlen > 1024 || (kind == 2 && vlen != 0))
      throw Reject{"invalid_payload_length", at};
    const std::size_t frame = header_bytes + klen + vlen + trailer_bytes;
    if (frame > 1118) throw Reject{"invalid_frame_length", at};
    if (left < frame) throw Reject{"possible_partial_tail_no_repair_authority", at};
    std::uint32_t a = 1, b = 0;
    for (std::size_t i = at; i < at + frame - trailer_bytes; i++) {
      a = (a + d[i]) % 65521;
      b = (b + a) % 65521;
    }
    const std::size_t trailer = at + frame - trailer_bytes;
    if (u16(d, trailer) != a || u16(d, trailer + 2) != b)
      throw Reject{"checksum_mismatch", at};
    operations.insert(operation);
    records++;
    last_sequence = sequence;
    last_generation = generation;
    at += frame;
  }
  return Result{records, d.size(), last_sequence, last_generation};
}
void append16(Bytes& out, std::uint32_t n) {
  out.push_back(static_cast<Byte>(n & 255));
  out.push_back(static_cast<Byte>((n >> 8) & 255));
}
void append32(Bytes& out, std::uint32_t n) {
  for (unsigned i=0; i<4; i++) out.push_back(static_cast<Byte>((n >> (8 * i)) & 255));
}
Bytes frame(std::uint32_t tenant, std::uint32_t generation, std::uint32_t sequence,
            std::uint32_t operation, Byte kind, std::span<const Byte> key,
            std::span<const Byte> value) {
  if (tenant == 0 || tenant > 2147483647U || generation == 0 || generation > 2147483647U ||
      sequence == 0 || sequence > 2147483647U || operation == 0 || operation > 2147483647U ||
      (kind != 1 && kind != 2) || key.empty() || key.size() > 64 || value.size() > 1024 ||
      (kind == 2 && !value.empty())) throw std::invalid_argument("invalid fixture frame");
  Bytes bytes{79,68,66,49,1,kind};
  append32(bytes,tenant); append32(bytes,generation);
  append32(bytes,sequence); append32(bytes,operation);
  append16(bytes,static_cast<std::uint32_t>(key.size()));
  append16(bytes,static_cast<std::uint32_t>(value.size()));
  bytes.insert(bytes.end(),key.begin(),key.end());
  bytes.insert(bytes.end(),value.begin(),value.end());
  std::uint32_t a=1,b=0;
  for (Byte byte:bytes) { a=(a+byte)%65521; b=(b+a)%65521; }
  append16(bytes,a); append16(bytes,b);
  return bytes;
}
void write(const std::filesystem::path& path, const Bytes& data) {
  std::ofstream f(path, std::ios::binary | std::ios::trunc);
  if (!f || !f.write(reinterpret_cast<const char*>(data.data()),
                       static_cast<std::streamsize>(data.size()))) throw std::runtime_error("fixture output error");
}
void emit(const std::filesystem::path& dir) {
  std::filesystem::create_directories(dir);
  write(dir / "empty.wal", {});
  const Bytes key{107}, value{118};
  const auto put = frame(7,3,1,42,1,key,value);
  write(dir / "single.wal", put);
  Bytes two = put;
  auto second = frame(7,4,2,43,2,key,{});
  two.insert(two.end(),second.begin(),second.end());
  write(dir / "two.wal", two);
  const Bytes binary_key{0,255,128}, binary_value{0,1,255,128};
  write(dir / "binary.wal", frame(7,2147483647U,1,2147483647U,1,binary_key,binary_value));
  Bytes max_key(64), max_value(1024);
  for (std::size_t i=0;i<max_key.size();i++) max_key[i]=static_cast<Byte>(i);
  for (std::size_t i=0;i<max_value.size();i++) max_value[i]=static_cast<Byte>(i&255);
  write(dir / "maximum.wal", frame(7,3,1,42,1,max_key,max_value));
}
void emit_negative(const std::filesystem::path& dir) {
  std::filesystem::create_directories(dir);
  const Bytes k{107}, v{118};
  const Bytes put=frame(7,3,1,42,1,k,v);
  auto bad=put;
  bad[0]=0; write(dir / "invalid_magic.wal",bad);
  bad=put; bad[4]=2; write(dir / "invalid_version.wal",bad);
  bad=put; bad[5]=3; write(dir / "invalid_kind.wal",bad);
  bad=put; bad[21]=128; write(dir / "high_bit_op.wal",bad);
  bad=put; bad[13]=128; write(dir / "high_bit_generation.wal",bad);
  bad=put; bad[22]=0; write(dir / "invalid_key_length.wal",bad);
  bad=put; bad[24]=1; bad[25]=4; write(dir / "invalid_value_length.wal",bad);
  bad=put; bad.back()^=1; write(dir / "checksum_corrupt.wal",bad);
  bad=put; bad.pop_back(); write(dir / "truncated_tail.wal",bad);
  bad=put; bad.push_back(0); write(dir / "unverified_short_tail.wal",bad);
  bad=Bytes{79}; write(dir / "short_header.wal",bad);
  write(dir / "foreign_tenant.wal",frame(8,3,1,42,1,k,v));
  bad=put;
  auto second=frame(7,2,2,43,2,k,{});
  bad.insert(bad.end(),second.begin(),second.end());
  write(dir / "generation_regression.wal",bad);
  bad=put; second=frame(7,4,3,43,2,k,{});
  bad.insert(bad.end(),second.begin(),second.end());
  write(dir / "sequence_gap.wal",bad);
  bad=put; second=frame(7,4,2,42,2,k,{});
  bad.insert(bad.end(),second.begin(),second.end());
  write(dir / "duplicate_operation.wal",bad);
  bad=put; bad[5]=2; write(dir / "delete_nonempty_value.wal",bad);
  // Two distinct payloads share the same Adler-32 trailer. Never infer MAC.
  const Bytes collision_key{60,60,60}, collision_value{42};
  bad=frame(7,3,1,42,1,collision_key,collision_value);
  bad[26]=61; bad[27]=58; bad[28]=61;
  write(dir / "valid_adler_collision.wal",bad);
  bad.clear();
  for (std::uint32_t i=1;i<=4097;i++) {
    auto next=frame(7,3,i,i,2,k,{});
    bad.insert(bad.end(),next.begin(),next.end());
  }
  write(dir / "too_many_records.wal",bad);
  write(dir / "too_large.wal",Bytes(max_bytes+1,0));
}
void emit_mutants(const std::filesystem::path& dir) {
  std::filesystem::create_directories(dir);
  const Bytes key{107}, value{118};
  const Bytes good = frame(7,3,1,42,1,key,value);
  for (std::size_t i=0; i<good.size(); i++) {
    Bytes mutated=good;
    mutated[i]^=1;
    char filename[40];
    std::snprintf(filename,sizeof(filename),"mutate-%02zu.wal",i);
    write(dir / filename,mutated);
  }
}
Bytes read(const std::filesystem::path& path) {
  std::error_code error;
  auto size=std::filesystem::file_size(path,error);
  if (error) throw std::runtime_error("fixture stat failed");
  if (size > max_bytes) throw Reject{"wal_size_out_of_bounds",0};
  std::ifstream input(path,std::ios::binary);
  if (!input) throw std::runtime_error("fixture open failed");
  Bytes bytes(static_cast<std::size_t>(size));
  if (size && !input.read(reinterpret_cast<char*>(bytes.data()),static_cast<std::streamsize>(size)))
    throw std::runtime_error("fixture short read");
  return bytes;
}
} // namespace
int main(int argc,char**argv) {
  try {
    if (argc == 3 && std::string_view(argv[1]) == "emit") {
      emit(argv[2]); return 0;
    }
    if (argc == 3 && std::string_view(argv[1]) == "emit-negative") {
      emit_negative(argv[2]); return 0;
    }
    if (argc == 3 && std::string_view(argv[1]) == "emit-mutants") {
      emit_mutants(argv[2]); return 0;
    }
    if (argc != 4 || std::string_view(argv[1]) != "inspect") {
      std::cerr << "Usage: odb1_wire_interop emit DIR | emit-negative DIR | inspect FILE TENANT\n"; return 64;
    }
    std::uint32_t tenant=0;
    const std::string_view text=argv[3];
    const auto parsed=std::from_chars(text.data(),text.data()+text.size(),tenant);
    if (parsed.ec != std::errc{} || parsed.ptr != text.data()+text.size() ||
        text.empty() || tenant == 0 || tenant > 2147483647U) return 64;
    const Bytes data=read(argv[2]);
    const Result r=inspect(data,tenant);
    std::cout << "OK records=" << r.records << " bytes=" << r.bytes
              << " last_sequence=" << r.sequence << " last_generation=" << r.generation << '\n';
    return 0;
  } catch (const Reject& error) {
    std::cout << "INVALID reason=" << error.reason << " at=" << error.offset << '\n';
    return 1;
  } catch (const std::exception& error) {
    std::cerr << "ODB1 native host harness error: " << error.what() << '\n';
    return 64;
  }
}

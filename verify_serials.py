serials = [f"SN260724N500{i:03d}" for i in range(201, 601)]
assert len(serials) == 400
assert len(set(serials)) == 400
assert serials[0] == "SN260724N500201"
assert serials[-1] == "SN260724N500600"
assert "SN260724N500570" in serials
print("SERIAL_LIST_OK: 400 unique SN, 201..600, including 570")

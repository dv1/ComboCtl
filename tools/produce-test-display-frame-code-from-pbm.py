#!/usr/bin/env python3

# This is a simple tool to convert a PBM file with a Combo display frame inside to
# Kotlin code that can be added to comboctl/parser/TestDisplayFrames.kt as an extra
# frame for unit tests.
#
# PBM files are produced by the javafxApp when the "Dump RT frames to PBM images"
# box is checked. The app will then dump each and every received RT display frame
# to a PBM file with the filename being "frameXXXXX.pbm", XXXXX being the frame
# number, starting at 00000.

import os,sys,argparse

argparser = argparse.ArgumentParser()
argparser.add_argument('-i', '--input-pbm', required=True, help='Filename of PBM file to read from')
argparser.add_argument('-n', '--test-frame-varname', required=True, help='Kotlin variable name for the generated test display frame')

args = argparser.parse_args()
input_pbm = args.input_pbm
test_frame_varname = args.test_frame_varname

sys.stderr.write(f'Reading PBM data from "{input_pbm}" and generating code, variable name is "{test_frame_varname}"\n')

try:
    with open(input_pbm, 'r') as f:
        print(f"val {test_frame_varname} = makeDisplayFrame(arrayOf(")
        counter = 0
        for line in f:
            if counter > 2:
                print(',')
            if counter >= 2:
                print('    "' + line.strip().replace('0', ' ').replace('1', '█') + '"', end='')
            counter += 1
        print('')
        print("))")
except FileNotFoundError as f:
    sys.stderr.write(f'Could not find file "{input_pbm}"\n')

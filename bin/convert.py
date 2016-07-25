import subprocess
from osgeo import gdal, osr
import logging
import sys
import math
from multiprocessing import Process
import os
from os.path import basename
import time 

logger = logging.getLogger('convert')

LEVELS = 3
TILESIZE = 16384
GDALCACHEMAXVALUE = 512
GDALCACHEMAX= " --config GDAL_CACHEMAX " + str (GDALCACHEMAXVALUE) + " "
numThreads = 5
numberOfDigits = 4
deleteOriginal = False  

def deleteShape(shapeFile):
    baseNameNoExt = os.path.splitext(os.path.basename(shapeFile))[0]
    outputFolder = os.path.dirname(shapeFile)
    os.remove(os.path.join(outputFolder, baseNameNoExt + ".shp"))
    os.remove(os.path.join(outputFolder, baseNameNoExt + ".prj"))
    os.remove(os.path.join(outputFolder, baseNameNoExt + ".dbf"))
    os.remove(os.path.join(outputFolder, baseNameNoExt + ".shx"))
    os.remove(os.path.join(outputFolder, baseNameNoExt + ".fix"))

def extractFootprint(outputFile):
    start_time = int(round(time.time() * 1000))
    command = ['extractfootprint.sh']
    extractString = outputFile + " SHAPEFILE WKB"
    command = appendOptions(extractString, command)
    execute (command)
    stop_time = int(round(time.time() * 1000))
    delta = int((stop_time - start_time) /1000)
    print "Extracting footprint tooks  " + str(delta) + " seconds"

def extractBitMask(outputFolder, outputFile, baseNameNoExt):
    command = ['gdal_rasterize']
    shapeFile = os.path.join(outputFolder, baseNameNoExt + ".shp")
    maskFile = outputFile + ".msk"
    rasterize = "-ot Byte -l " + baseNameNoExt + " -CO TILED=YES -CO BLOCKXSIZE=512 -CO BLOCKYSIZE=512 -CO NBITS=1 -CO PHOTOMETRIC=MINISBLACK -CO COMPRESS=DEFLATE -a cat -burn 1 -ts " + str(TILESIZE) + " " + str(TILESIZE) + " " + str(shapeFile) + " " + maskFile
    command = appendOptions(rasterize, command)
    execute (command)
    return maskFile

def setMask(outputFolder, outputFile, baseNameNoExt, outputVRT):
    command = ['gdal_translate']
    maskedTif = os.path.join(outputFolder, baseNameNoExt + "_.tif")
    addBand = "-of GTIFF -CO PHOTOMETRIC=RGB -CO ALPHA=NO -CO TILED=YES -CO COMPRESS=DEFLATE -b 1 -b 2 -b 3 -b 4 -mask 5 --config GDAL_TIFF_INTERNAL_MASK YES" + GDALCACHEMAX + outputVRT + " " + maskedTif
    command = appendOptions(addBand, command)
    execute (command)
    return maskedTif

def addMaskToVrt(outputFolder, outputFile, baseName):
    baseNameNoExt = os.path.splitext(baseName)[0]
    srcRaster = gdal.Open(outputFile)
    numBands = srcRaster.RasterCount
    band = srcRaster.GetRasterBand(1)
    driver = gdal.GetDriverByName('VRT')
    outputVRT = os.path.join(outputFolder, baseNameNoExt + ".vrt")
    xSize = srcRaster.RasterXSize
    ySize = srcRaster.RasterYSize
    outFile =  open(outputVRT, "w")
    outFile.write('<VRTDataset rasterXSize="%i" rasterYSize="%i">\n'% (xSize, ySize))
    geoTransform = str(srcRaster.GetGeoTransform())
    outFile.write('  <GeoTransform>' + geoTransform[1:len(geoTransform)-1] + '</GeoTransform>\n')
    outFile.write('  <SRS>' + str(srcRaster.GetProjection()) + '</SRS>\n')
    for bandIndex in range(1, numBands+1):
        outFile.write('    <VRTRasterBand dataType="Byte" band="%i">\n'%bandIndex)
        outFile.write('      <SimpleSource>\n')
        outFile.write('        <SourceFilename relativeToVRT="1">%s</SourceFilename>\n' % baseName)
        outFile.write('        <SourceBand>%i</SourceBand>\n' % bandIndex)
        outFile.write('        <SourceProperties RasterXSize="%i" RasterYSize="%i" DataType="Byte" BlockXSize="256" BlockYSize="256"/>\n' % (xSize, ySize))
        outFile.write('        <SrcRect xOff="0" yOff="0" xSize="%i" ySize="%i"/>\n' % (xSize, ySize))
        outFile.write('        <DstRect xOff="0" yOff="0" xSize="%i" ySize="%i"/>\n' % (xSize, ySize))
        outFile.write('      </SimpleSource>\n')
        outFile.write('    </VRTRasterBand>\n')

    outFile.write('    <VRTRasterBand dataType="Byte" band="5">\n')
    outFile.write('      <SimpleSource>\n')
    outFile.write('        <SourceFilename relativeToVRT="1">%s</SourceFilename>\n' % (baseName+'.msk'))
    outFile.write('        <SourceBand>1</SourceBand>\n')
    outFile.write('        <SourceProperties RasterXSize="%i" RasterYSize="%i" DataType="Byte" BlockXSize="256" BlockYSize="256"/>\n' % (xSize, ySize))
    outFile.write('        <SrcRect xOff="0" yOff="0" xSize="%i" ySize="%i"/>\n' % (xSize, ySize))
    outFile.write('        <DstRect xOff="0" yOff="0" xSize="%i" ySize="%i"/>\n' % (xSize, ySize))
    outFile.write('      </SimpleSource>\n')
    outFile.write('    </VRTRasterBand>\n')
    outFile.write('</VRTDataset>\n')
    outFile.close()
    return outputVRT

def addOverviews(outputFile, isRGB):
    command = ['gdaladdo']
    gdaladdo = "-r average --config COMPRESS_OVERVIEW JPEG "
    if (isRGB):
        gdaladdo +="--config PHOTOMETRIC_OVERVIEW YCBCR " 
    gdaladdo+= outputFile + " 2 4"
    command = appendOptions(gdaladdo, command)
    execute (command)
    

def extract(outputFile, isRGB):
    start_time = int(round(time.time() * 1000))
    baseName = os.path.basename(outputFile)
    baseNameNoExt = os.path.splitext(baseName)[0]
    outputFolder = os.path.dirname(outputFile)
    command = ['gdal_translate']
    suffix = "RGB" if isRGB else "IR"
    converted = os.path.join(outputFolder, baseNameNoExt + "_" + suffix + ".tif")
    extractCommand = "-of GTIFF -CO ALPHA=NO -CO TILED=YES -CO COMPRESS=JPEG --config GDAL_TIFF_INTERNAL_MASK YES " 
    if (isRGB):
        extractCommand += "-b 1 -b 2 -b 3 -CO PHOTOMETRIC=YCBCR "
    else:
        extractCommand += "-b 4 "
    extractCommand += outputFile + " " + converted
    command = appendOptions(extractCommand, command)
    execute (command)
    addOverviews(converted, isRGB)
    stop_time = int(round(time.time() * 1000))
    delta = int((stop_time - start_time) /1000)
    print "Extracting " + ("RGB" if isRGB else "IR") + " and adding Overviews tooks " + str(delta) + " seconds"

def convert(inputVrt, outputFile, srcX, srcY, tileSize):
    baseName = os.path.basename(outputFile)
    baseNameNoExt = os.path.splitext(baseName)[0]
    outputFolder = os.path.dirname(outputFile)
    command = ['gdal_translate']
    crop = "-srcwin " + str(srcX) + " " + str(srcY) + " " + str(tileSize) + " " + str(tileSize) + " -CO BLOCKXSIZE=512 -CO BLOCKYSIZE=512 -CO TILED=YES -CO PHOTOMETRIC=RGB -CO ALPHA=NO" + GDALCACHEMAX + inputVrt +  " " + outputFile
    command = appendOptions(crop, command)
    execute (command)

    # Extracting footprint
    extractFootprint(outputFile)

    # Extracting bit mask
    maskFile = extractBitMask(outputFolder, outputFile, baseNameNoExt)
    shapeFile = os.path.join(outputFolder, baseNameNoExt + ".shp")
    #deleteShape(shapeFile)

    # Setting vrt
    outputVRT = addMaskToVrt(outputFolder, outputFile, baseName)

    #Setting bit mask
    maskedTif = setMask(outputFolder, outputFile, baseNameNoExt, outputVRT)
    
    if deleteOriginal:
        os.remove(outputFile)
    else:
        old = os.path.join(outputFolder, baseNameNoExt + "_old.tif")
        os.rename(outputFile, old)
    os.rename(maskedTif, outputFile)
    os.remove(outputVRT)
    os.remove(maskFile)
    extract(outputFile, True)
    extract(outputFile, False)
    
    

def buildFile(outputFolder, columnIndex, rowIndex, digits, suffix=None):
    row = str(rowIndex).zfill(digits)
    column = str(columnIndex).zfill(digits)
    fileName = "R" + row + "C" + column
    fileName += (("_" + suffix) if suffix is not None else "")
    fileName += ".tif"
    outFile = os.path.join(outputFolder, fileName)
    return outFile

def createChunks(inputVrt, outputFolder, startX, startY, limitX, limitY, tileSize):
    processes = 0
    for tileY in range(startY, limitY):
        for tileX in range(startX, limitX):
            srcX = tileX * tileSize
            srcY = tileY * tileSize
            column = tileX+1
            row = tileY+1
            outFile = buildFile(outputFolder, column, row, numberOfDigits)
            start_time = int(round(time.time() * 1000))
            convert(inputVrt, outFile, srcX, srcY, tileSize)
            stop_time = int(round(time.time() * 1000))
            delta = int((stop_time - start_time) /1000)
            print "converting chunk R" + str(row) + "C" + str(column) + " tooks " + str(delta) + " seconds"

def split(inputVrt, outputFolder, tileSize, FIRSTX, FIRSTY, WIDTH, HEIGHT):
    if not os.path.exists(outputFolder):
        os.makedirs(outputFolder)
    srcRaster = gdal.Open(inputVrt)
    driver = gdal.GetDriverByName('GTiff')

    rasterX = srcRaster.RasterXSize
    rasterY = srcRaster.RasterYSize
    horizontalChunks = int(math.ceil(rasterX/tileSize))
    verticalChunks = int(math.ceil(rasterY/tileSize))
    startX = FIRSTX
    startY = FIRSTY
    noLimits = False
    limitX = horizontalChunks if noLimits else startX + WIDTH
    limitY = verticalChunks if noLimits else startY + HEIGHT
    createChunks(inputVrt, outputFolder, startX, startY, limitX, limitY, tileSize)
    for level in range(1, LEVELS+1):
        start_time = int(round(time.time() * 1000))
        levelS = str(level)
        base = None if level == 1 else str(level -1)
        levelFactor = int(math.pow(4, level))
        levelBefore = 1 if level == 1 else int(math.pow(4, (level-1)))
        
        for yPieces in range(0,int((limitY - startY)/levelFactor)):
            for xPieces in range(0, int((limitX - startX)/levelFactor)):
                xOffset = int(startX/levelBefore) + (xPieces*4)
                yOffset = int(startY/levelBefore) + (yPieces*4)
                produceLevel(outputFolder, xOffset, yOffset, levelS, True, base)
                produceLevel(outputFolder, xOffset, yOffset, levelS, False, base)
        stop_time = int(round(time.time() * 1000))
        delta = int((stop_time - start_time) /1000)
        print "producing level " + levelS + " tooks " + str(delta) + " seconds"
    
def produceLevel(outputFolder, startX, startY, level, isRGB, base=None):
    tileList = ""
    for tileY in range(startY, startY + 4):
        for tileX in range(startX, startX + 4):
            suffix = "RGB" if isRGB else "IR"
            if base is not None:
                suffix += "_" + base
            inputFile = buildFile(outputFolder, tileX+1, tileY+1, numberOfDigits, suffix)
            tileList+= inputFile + " "
    tileList = tileList[:-1] #Cut away last white space
    vrt = produceVrt(outputFolder, (startX / 4) + 1, (startY / 4) + 1, level, isRGB, tileList)
    rescaleForLevel(vrt, isRGB)

def produceVrt(outputFolder, column, row, level, isRGB, tileList):
    command = ['gdalbuildvrt']
    outFile = buildFile(outputFolder, column, row, numberOfDigits, ("RGB_" if isRGB else "IR_") + level)
    baseName = os.path.basename(outFile)
    baseNameNoExt = os.path.splitext(baseName)[0]
    outputVrt = os.path.join(outputFolder, baseNameNoExt + ".vrt")
    print str(outputVrt)
    buildVrt = outputVrt + " "
    buildVrt += tileList 
    command = appendOptions(buildVrt, command)
    execute (command)
    return outputVrt

def rescaleForLevel(inputFile, isRGB):
    baseName = os.path.basename(inputFile)
    baseNameNoExt = os.path.splitext(baseName)[0]
    outputFolder = os.path.dirname(inputFile)
    extracted = os.path.join(outputFolder, baseNameNoExt + ".tif")
    command = ['gdal_translate']
    rescale = "-outsize 12.5% 12.5% -of GTIFF -CO ALPHA=NO -CO TILED=YES -CO COMPRESS=JPEG --config GDAL_TIFF_INTERNAL_MASK YES "
    if isRGB:
        rescale+= "-CO PHOTOMETRIC=YCBCR "
    rescale += (inputFile + " " + extracted)
    command = appendOptions(rescale, command)
    execute (command)
    addOverviews(extracted, isRGB)



def appendOptions(options, command):
    if (options is not None):
        options = options.replace('\"','')
        options = options.split(' ')
        for option in options:
            command.append(option)
    return command


def execute(command):
    print('Executing: ' + getCommandString(command))
    try:
        command_line_process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

        process_output, _ =  command_line_process.communicate()
        print(process_output)
    except (OSError) as exception:
        print('Exception occured')
        print('Subprocess failed: ' + getCommandString(command))
        raise
    #else:
        # no exception was raised
        #print('Done')
    return True

def getCommandString(command):
    if (command):
        return ' '.join(str(e) for e in command)
    return ''


def main():
    # Parse command line arguments.
    argv = sys.argv
    numParams = len(argv)
    inputFile = argv[1]
    outputFolder = argv[2]
    FIRSTX = 0
    FIRSTY = 0
    WIDTH = 32
    HEIGHT = 32
    if (numParams == 7):
        FIRSTX = int(argv[3])
        FIRSTY = int(argv[4])
        WIDTH = int(argv[5])
        HEIGHT = int(argv[6])
        print "startx = " + str(FIRSTX) + " starty = " + str(FIRSTY)
    split(inputFile, outputFolder, TILESIZE, FIRSTX, FIRSTY, WIDTH, HEIGHT)

if __name__ == '__main__':
    main()
